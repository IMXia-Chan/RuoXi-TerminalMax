﻿﻿# 常驻的 UI Automation 助手:从 stdin 读一行 JSON,往 stdout 写一行 JSON。
#
# 为什么是 PowerShell:.NET 的 UIAutomationClient 是 Windows 自带的,零安装、零依赖。
# 这台机器上 pip 够不到源(代理全关),comtypes/uiautomation 装不上;而 UIA 的
# COM 接口用 ctypes 裸写要三百行 vtable/BSTR/SAFEARRAY,不值当。
#
# 为什么常驻而不是每次起一个:PowerShell 冷启动 + 加载 UIAutomation 程序集要
# 几百毫秒,而查询本身只要几十毫秒。常驻之后每次查询就是纯查询的钱。
#
# ⚠️ 本文件必须存成 **UTF-8 with BOM**。PowerShell 5.1 读无 BOM 的 .ps1 会按
#    ANSI(GBK)解,中文全乱,然后报一个和编码八竿子打不着的语法错 —— 已经踩过。
#    (uia.py 里有对应的检查,别再手工另存成 UTF-8。)
#
# 协议(全部单行 JSON,由 uia.py 驱动):
#   收 {"cmd":"ping"}
#   收 {"cmd":"list","window":"微信","limit":40}          只看屏幕内、只收可点类型
#   收 {"cmd":"find","name":"发送","window":"微信"}        **连离屏一起搜**(元素带 offscreen 标志)
#   收 {"cmd":"ocr","window":"微信","limit":200}          整屏识字,自绘界面也能认
#   收 {"cmd":"patterns","name":"X"}                      只读诊断:目标支持哪些 UIA Pattern
#   收 {"cmd":"invoke","name":"X"}                        让控件自己动作(Invoke/Select/Toggle/Expand)
#                                                         或 ScrollIntoView 后把坐标交回去点
#   回 {"ok":true,"elements":[{"name":..,"type":..,"offscreen":..,"x":..,"y":..,"w":..,"h":..,"cx":..,"cy":..}]}

[Console]::OutputEncoding = [Text.Encoding]::UTF8

Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes

# GetForegroundWindow 得靠 P/Invoke —— UIA 自己只给「有焦点的**元素**」,
# 不给「前台**窗口**」,而我们要的是后者。
Add-Type -Namespace Ruoxi -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool GetCursorInfo(ref CURSORINFO pci);
[DllImport("user32.dll")] public static extern IntPtr LoadCursorW(IntPtr hInstance, IntPtr lpCursorName);
[StructLayout(LayoutKind.Sequential)]
public struct POINTAPI { public int x; public int y; }
[StructLayout(LayoutKind.Sequential)]
public struct CURSORINFO {
    public int cbSize; public int flags; public IntPtr hCursor; public POINTAPI ptScreenPos;
}
'@

# 鼠标指针的**形状** —— 「这一点是不是能打字的地方」最便宜也最准的那条判据。
#
# 为什么不能用 UIA 单独扛(2026-10-04 实测):
#   · Edge 里 <input> 确实在树里(`Edit | an input box | 1042,385 476x36`),
#     但**只有不带缓存的遍历才看得见**(见下面 probe 里那段说明);
#   · 更要命的是微信那种自绘界面,UIA 整个窗口只暴露 1 个后代 —— 树里根本没有;
#   · 而**指针形状不管界面谁画的都有效**:文字输入区一律是 I 型(beam),
#     链接/按钮是手型,普通区域是箭头。实测 Edge 一个窗口上同时出现三种。
#
# 代价(必须说清楚,别当成万能):**浏览器里可选中的正文也是 I 型** ——
# 点一段网页文字也会弹键盘。这是**假阳性**,用户按一下返回就没了;
# 反过来漏判才是致命的(点了输入框却不出键盘)。两个方向的代价不对称,
# 所以这里**偏向弹出来**。
function Get-CursorKind {
    try {
        $ci = New-Object Ruoxi.Win32+CURSORINFO
        $ci.cbSize = [System.Runtime.InteropServices.Marshal]::SizeOf($ci)
        if (-not [Ruoxi.Win32]::GetCursorInfo([ref]$ci)) { return 'unknown' }
        $h = $ci.hCursor
        if ($h -eq [IntPtr]::Zero) { return 'hidden' }
        # 标准指针。应用自己 LoadCursor 出来的文本指针句柄不同 —— 那种只能算 unknown。
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32513)) { return 'text' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32512)) { return 'arrow' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32515)) { return 'cross' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32649)) { return 'hand' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32514)) { return 'wait' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32648)) { return 'no' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32650)) { return 'help' }
        if ($h -eq [Ruoxi.Win32]::LoadCursorW([IntPtr]::Zero, [IntPtr]32516)) { return 'sizeall' }
        return 'other'
    } catch { return 'unknown' }
}

$AE   = [System.Windows.Automation.AutomationElement]
$TS   = [System.Windows.Automation.TreeScope]
$COND = [System.Windows.Automation.Condition]::TrueCondition
$ROOT = $AE::RootElement

# 只有这些类型值得让模型看见。纯 Text/Image/Pane 会把列表淹掉 ——
# 实测 VS Code 一个窗口就有 3373 个后代元素、2521 个有名字,
# 全塞给 4B(8192 上下文)等于让它什么都看不见。
$ACTIONABLE = @(
    'Button', 'MenuItem', 'Edit', 'ComboBox', 'ListItem',
    'TabItem', 'CheckBox', 'RadioButton', 'Hyperlink', 'TreeItem',
    'SplitButton', 'Menu', 'Slider'
)

function Get-Rect($el, [bool]$useCache = $false) {
    # $useCache:走 CacheRequest 批量取回来的值。**缓存里没有这个属性会抛异常**,
    # 所以只有调用方明确把 BoundingRectangle 加进 CacheRequest 了才能传 true。
    try {
        $r = if ($useCache) { $el.Cached.BoundingRectangle } else { $el.Current.BoundingRectangle }
    } catch { return $null }
    if ([double]::IsNaN($r.X) -or [double]::IsNaN($r.Width)) { return $null }
    if ($r.Width -le 0 -or $r.Height -le 0) { return $null }
    return @{
        x = [int]$r.X; y = [int]$r.Y
        w = [int]$r.Width; h = [int]$r.Height
        cx = [int]($r.X + $r.Width / 2); cy = [int]($r.Y + $r.Height / 2)
    }
}

function Get-TopWindow([string]$needle) {
    if (-not $needle) {
        $h = [Ruoxi.Win32]::GetForegroundWindow()
        if ($h -eq [IntPtr]::Zero) { return $null }
        try { return $AE::FromHandle($h) } catch { return $null }
    }
    foreach ($w in $ROOT.FindAll($TS::Children, $COND)) {
        if ($w.Current.Name -and $w.Current.Name -like "*$needle*") { return $w }
    }
    return $null
}

# 控件名不会长。实测踩的:VS Code 里有个容器的 Name 是**整篇文档/终端的内容**,
# 里面恰好含「开始」「微信」这种词,子串匹配一下就把它捞上来了 —— 而它根本不是
# 控件,是内容。点击它会点进正文中间。超过这个长度的名字一律不当控件名看。
$MAXNAME = 60

# $allowOff:要不要连**离屏**的元素一起收。
#
# 这条以前是硬砍的(`if IsOffscreen { return $null }`),理由是「看不见的东西点了
# 也不知道点哪」。但实测(2026-10-02)发现代价极大:VS Code 一个窗口 972 个可点
# 控件,**909 个(94%)是离屏的** —— 也就是说 UIA 明明告诉我们了,是我们自己扔掉。
#
# 正确的分法不是「扔不扔」,而是**分场合**:
#   - list(给模型「看看有什么」)—— 继续只给屏幕内的,否则 909 个元素直接淹掉 4B 的上下文;
#   - find(模型说「我要点 X」)  —— 连离屏一起搜,因为模型问的是「有没有」,不是「看不看得见」。
#
# 离屏元素照收,但标上 offscreen,并**排在屏幕内的后面**(看得见的动起来更可预测)。
# 另外它没有包围盒(没画出来嘛,矩形是空的),所以坐标给 0 —— 调用方靠 offscreen
# 这个标志决定该怎么动它(见 cmd=invoke:让控件自己动作,而不是点像素)。
function New-El($e, $nm, [int]$rank, [bool]$anyType, [bool]$allowOff = $false, [bool]$useCache = $false) {
    # $nm 由调用方**已经读过一次**,这里直接用 —— 每个属性读都是一次跨进程 COM
    # 调用,子串分支要过几千个元素,省一次就是省几千次。
    # $useCache=true 时其余的属性也从 CacheRequest 里取(见 Find-Hits 子串分支)。
    if ($useCache) {
        $t = $e.Cached.ControlType.ProgrammaticName.Replace('ControlType.', '')
    } else {
        $t = $e.Current.ControlType.ProgrammaticName.Replace('ControlType.', '')
    }
    $act = ($ACTIONABLE -contains $t)
    if (-not $act -and -not $anyType) { return $null }

    $isOff = $false
    try {
        $isOff = if ($useCache) { $e.Cached.IsOffscreen } else { $e.Current.IsOffscreen }
    } catch { }
    if ($isOff -and -not $allowOff) { return $null }

    # ★★ 非可点类型 **且** 离屏 —— 既点不到、也命令不动,是纯噪声,丢。
    #
    # 2026-10-03 实测栽在这:VS Code 当前台时 `find("微信")` / `find("发送")` /
    # `find("文件传输助手")` 三个**不同的名字返回的是同一个东西** —— 编辑器正文里
    # 一段滚出视野的 `Text` 节点(真实矩形 `333,-10395 33x17`,即 y 在一万像素外),
    # 而它的名字恰好就是我们自己打的那几个词。它的 cx/cy 按约定是 0/0。
    #
    # 为什么"离屏"这条判据在这里是关键:actionable 类型(Button 等)**离屏也收**,
    # 因为可以走 cmd=invoke 让控件自己动作(见上面那段注释);但 `Text` 只支持
    # Text/ScrollItem 两种 pattern,**没有 Invoke 可发** —— 收回来只能落进
    # 「找到了但在屏幕外,而且不接受直接命令」那句死胡同,然后再点也点不动。
    # 收它没有任何一条路能通向"点中",所以就地丢掉。
    #
    # 注意判据是 `$isOff -and -not $act` **两条一起**,不是单看类型:
    # **屏幕内**的非可点元素照样留着 —— 一张看得见的图片/一段看得见的文字,
    # 按坐标是点得到的(模型说"点那张图"时它才有用)。只有"看不见 + 命令不动"
    # 这个交集才是真噪声。
    #
    # 代价说清楚:理论上存在"非可点类型却支持 Invoke"的控件(某些框架把可点图片
    # 暴露成 Image),这种离屏元素以前能被 invoke 到,现在会被丢掉。取舍是:
    # 实测踩到的是**必然死路**的那一类(收回来只能落进"找到了但动不了"),
    # 而那种控件只是**理论可能**;而且 find 是热路径、过几千个元素,
    # 不可能为了它去逐个读 GetSupportedPatterns。真要那种能力,该走 patterns。
    if ($isOff -and -not $act) { return $null }

    # ⚠️ 离屏元素的 BoundingRectangle **不要去读**:一是慢(它常常要现算),
    #    二是读出来也用不上 —— 那是个「虚拟滚动空间」里的天文数字(实测
    #    y=-64728),照着点鼠标会飞到屏幕外。干脆给 0,把「不可用」写在脸上,
    #    由 offscreen 这个标志决定怎么动它(cmd=invoke)。要看真实矩形的
    #    用 cmd=patterns,那条路只查几个元素,读得起。
    if ($isOff) {
        $r = @{ x = 0; y = 0; w = 0; h = 0; cx = 0; cy = 0 }
    } else {
        $r = Get-Rect $e $useCache
        if ($null -eq $r) { return $null }
    }
    # 可点的类型优先(排在前面):同名时「Button 发送」该赢过「Pane 发送」。
    $bias = if ($act) { 0 } else { 100000 }
    if ($isOff) { $bias += 50000 }
    return [ordered]@{
        name = $nm; type = $t; rank = ($bias + $rank)
        offscreen = $isOff
        x = $r.x; y = $r.y; w = $r.w; h = $r.h; cx = $r.cx; cy = $r.cy
        # 元素本体,只给 cmd=invoke / cmd=patterns 用 —— 回给 Python 之前必须摘掉,
        # ConvertTo-Json 序列化一个 AutomationElement 会炸。
        _el = $e
    }
}

# $roots 是一组 @{ el = <元素>; scope = <TreeScope> },**不是**单个元素。
# 之所以是一组:桌面那一遍要能「跳过前台窗口」(见 Find-Scoped),那就得是
# 「root 的其余子窗口」的集合。
function Find-Hits($roots, [string]$want, [string]$ctype, [int]$limit, [bool]$anyType, [bool]$exact, [bool]$allowOff = $false) {
    $hits = @()
    if ($null -eq $roots -or $roots.Count -eq 0) { return $hits }

    if ($exact) {
        # 完全相等。UIA 的 FindAll 把条件下推给 provider,不必遍历整棵树 ——
        # 实测比全量枚举快一个量级(find '关闭' 166ms vs 全量 1385ms)。
        # 绝大多数调用走的是这条快路。
        $exactCond = New-Object System.Windows.Automation.PropertyCondition($AE::NameProperty, $want)
        # 收了离屏就不能「凑够 limit 就早退」—— 否则先撞见谁算谁,一个看不见的
        # 离屏控件会压掉屏幕上那个同名可见的。所以放宽上限、收完再排。
        $cap = if ($allowOff) { 400 } else { $limit }
        foreach ($root in $roots) {
            foreach ($e in $root.el.FindAll($root.scope, $exactCond)) {
                if ($ctype -and $e.Current.ControlType.ProgrammaticName.Replace('ControlType.','') -ne $ctype) { continue }
                # PropertyCondition 是**完全相等**匹配,所以它的 Name 必然就是 $want,
                # 不必再跨进程读一遍。
                $el = New-El $e $want 0 $anyType $allowOff
                if ($el) { $hits += $el; if ($hits.Count -ge $cap) { break } }
            }
            if ($hits.Count -ge $cap) { break }
        }
        if ($allowOff) { return @($hits | Sort-Object rank, name | Select-Object -First $limit) }
        return $hits
    }

    # 子串匹配:要全量枚举,慢,只在完全相等两条路都落空时才付这个钱。
    #
    # ⚡ 这里**必须**用 CacheRequest,否则慢得离谱。
    #    普通 FindAll 是「先把树拿回来,再一个属性一个属性跨进程去问」——
    #    每个元素的 Name / ControlType / IsOffscreen / BoundingRectangle 各算
    #    一次 COM 往返,几千个元素就是上万次。实测一次全量枚举 **3 秒**,
    #    而「找不到」要跑两遍(前台 + 桌面),一共 6.4 秒。
    #    CacheRequest 让 provider 在**这一趟遍历里**把要的属性一并捎回来,
    #    跨进程次数从「元素×属性」降到「遍历次数」。
    #
    #    ⚠️ 加进来的属性必须**恰好**是下面会用到的:想读没加的属性会抛异常,
    #       而多读没用的属性等于白花钱。
    $cr = New-Object System.Windows.Automation.CacheRequest
    $cr.Add($AE::NameProperty)
    $cr.Add($AE::ControlTypeProperty)
    $cr.Add($AE::IsOffscreenProperty)
    $cr.Add($AE::BoundingRectangleProperty)

    # ⚠️ CacheRequest 上**没有 Deactivate()** —— 它只有 Activate() / Push() / Pop()。
    #    `Activate()` 返回一个 IDisposable,靠它 Dispose 才是正道。
    #    踩过的坑:写 `$cr.Deactivate()` 时,PowerShell 默认 ErrorActionPreference
    #    是 Continue —— 这个「方法不存在」**不报错、不中断**,只是悄悄跳过,
    #    于是缓存一直挂在进程上影响后面所有查询,而现场看起来只是「找不到东西」。
    #    症状极难反推,所以这里显式 Dispose,并且整个遍历都在作用域内完成。
    $token = $cr.Activate()
    try {
        foreach ($root in $roots) {
            foreach ($e in $root.el.FindAll($root.scope, $COND)) {
                $nm = $e.Cached.Name
                if (-not $nm -or $nm.Length -gt $MAXNAME -or $nm -notlike "*$want*") { continue }
                if ($ctype -and $e.Cached.ControlType.ProgrammaticName.Replace('ControlType.','') -ne $ctype) { continue }
                $el = New-El $e $nm $nm.Length $anyType $allowOff $true
                if ($el) { $hits += $el }
            }
        }
    } finally {
        if ($null -ne $token) { $token.Dispose() }
    }
    return @($hits | Sort-Object rank, name)
}

# 按「范围顺序 + 精确优先」找一轮。find / invoke / patterns 三条命令共用,
# 免得三处各写一遍、各有各的偏差。
#
# 范围顺序(踩过的坑):**「整个桌面」必须和「前台窗口」平级**,不能只当兜底 ——
# 前台窗口里一个劣质子串命中,会把桌面那一步整个压掉,而开始按钮在任务栏里、
# 根本不属于任何应用窗口,于是永远轮不到它。
#
# → @{hits; nm} ;窗口关键词给了但找不到那个窗口时返回 $null(调用方翻译成报错)。
function Find-Scoped([string]$want, [string]$ctype, [int]$limit, $windowNeedle, [bool]$anyType, [bool]$allowOff) {
    $scopes = @()
    # $where = 「搜的是哪儿」。**一无所获时也要报出去** —— 上层拿它判断
    # 「这是个什么界面」,回空串等于把仅有的线索也扔了(实测:观察里的
    # window 会是空的,老师看到「窗口:(空)」比看到「窗口:微信」差得多)。
    $where = ''
    if ($windowNeedle) {
        $w = Get-TopWindow $windowNeedle
        if ($null -eq $w) { return $null }
        $where = $w.Current.Name
        $scopes += @{ roots = @(@{ el = $w; scope = $TS::Descendants }); nm = $where; desk = $false }
    } else {
        $fg = Get-TopWindow $null
        if ($null -ne $fg) {
            # 前台窗口也必须是 Subtree,理由和下面 rest 那半**镜像相同**:
            # FindAll(Descendants) 不含被调用的元素自身,而老写法从 root 做
            # Descendants 时,「前台窗口这个元素」是算作候选的。
            # 实测(2026-10-02,VS Code 当前台,连跑三轮):用 Descendants 时
            # 搜索集合恒比老写法**少 1 个**,用 Subtree 则恒为 0 —— 而这个 1
            # 就是「明明在却说没有」的最小形态,所以两个字都不能省。
            $where = $fg.Current.Name
            $scopes += @{ roots = @(@{ el = $fg; scope = $TS::Subtree }); nm = $where; desk = $false }

            # 桌面那一遍**跳过已经搜过的前台窗口**。
            #
            # 桌面 root 的子树本来就包含前台窗口,不跳就是白走一趟。实测(2026-10-02):
            # VS Code 当前台时它有 4526 个元素、一趟 2 秒,而桌面其余部分加起来才 200ms ——
            # 也就是说「找不到」那条路上 2 秒是纯浪费。
            # (反过来,前台是小窗口时冗余只有 55ms,这步就几乎不省 —— 别指望它万能。)
            #
            # 用 Subtree 而不是 Descendants:逐个搜 root 的子窗口时,得把子窗口
            # **自己**也算进去,合起来才等价于原来从 root 做 Descendants。
            #
            # ⚠️ 跳过只在 **Equals 明确返回 true** 时发生。万一判断失败我们只是
            #    多搜一遍(慢),绝不会少搜一个窗口(错)—— 宁可慢,不可漏。
            $rest = @()
            foreach ($c in $ROOT.FindAll($TS::Children, $COND)) {
                $same = $false
                try { $same = [System.Windows.Automation.AutomationElement]::Equals($c, $fg) } catch { }
                if (-not $same) { $rest += @{ el = $c; scope = $TS::Subtree } }
            }
            $scopes += @{ roots = $rest; nm = '整个桌面'; desk = $true }
        } else {
            $where = '整个桌面'
            $scopes += @{ roots = @(@{ el = $ROOT; scope = $TS::Descendants }); nm = $where; desk = $true }
        }
    }
    # 优先级:**完全相等的桌面** > 完全相等的前台 > 子串的桌面 > 子串的前台。
    # 也就是「是不是精确命中」比「在哪个窗口里」重要。
    #
    # ⚠️ 这个优先级**正好是 2026-10-03 那次误点的帮凶**:「整个桌面」那一遍排在
    # 前台那一遍前面,于是「别的窗口里有个精确同名(或碰巧含这几个字)的元素」
    # 会压过「当前窗口里的子串匹配」。上层现在拿 `desk` 把这一类命中直接丢掉
    # (见 ai_tools._scope_ok),但顺序本身也值得记一笔。
    # ★★ PowerShell 的数组展开,在这里埋过一个**静默**的假数字(2026-10-03 实测):
    #
    #   函数返回数组时,若数组里只有**一个**元素,PS 会把它"展开"成那个元素本身。
    #   所以 `Find-Hits` 只命中 1 个时返回的是**裸 hashtable**,不是长度为 1 的数组。
    #   而 hashtable 的 `.Count` 是**键数** —— 元素字典正好有 11 个键
    #   (name/type/rank/offscreen/x/y/w/h/cx/cy/_el),于是 `$h.Count` 报 **11**。
    #
    #   症状:`find("微信")` 真实命中 1 个,回包却写 `count: 11`;换 limit=5 还是
    #   1 个元素、还是 11。**这个假数字会一路传到手机** —— _obs() 拿它当
    #   `same_name`(「同名的还有几个」),而那是给经验库和云端老师看的。
    #
    #   修法:在**消费端**用 `@()` 把结果重新框成数组 —— 不能指望函数自己包,
    #   因为在输出那一刻展开就已经发生了。凡是拿 Find-Hits 结果的地方都要这样。
    foreach ($exact in @($true, $false)) {
        foreach ($s in $scopes) {
            if ($s.roots.Count -eq 0) { continue }
            $h = @(Find-Hits $s.roots $want $ctype $limit $anyType $exact $allowOff)
            # 同理:$h 可能是"一个 hashtable",这时 $h.Count 是**键数**不是命中数。
            # 用数组包住之后 .Count 才是真的数,(@() 的 Count 是 0,判空仍然成立)。
            if (@($h).Count -gt 0) { return @{ hits = @($h); nm = $s.nm; desk = $s.desk } }
        }
    }
    return @{ hits = @(); nm = $where; desk = $false }
}

# 摘掉 _el 再回给 Python —— ConvertTo-Json 序列化 AutomationElement 会炸。
function Public-El($e) {
    $o = [ordered]@{}
    foreach ($k in $e.Keys) { if ($k -ne '_el') { $o[$k] = $e[$k] } }
    return $o
}

function Reply($obj) {
    # 用 [Console]::Out 直写,绕开 PowerShell 的输出格式化管线 ——
    # 否则它会按 $OutputEncoding 转码,中文回程又变乱码。
    [Console]::Out.WriteLine(($obj | ConvertTo-Json -Compress -Depth 5))
    [Console]::Out.Flush()
}

# 一个元素「能不能打字」的全部判据,压成一个小对象。给 probe 用。
#
# ★ editable 判的是**能力**,不是类型名。光看 ControlType 两头都会错:
#   · 浏览器里的 <input>、VS Code 的编辑器都报 **Document**,按类型名判就漏;
#   · 计算器那种**只读**显示框报的是 **Edit**,按类型名判就误报 ——
#     用户点一下计算器的结果,手机上弹出一块键盘。
# 所以按 UIA 的 Pattern 判:能读写值(ValuePattern 且非只读)、
# 或者有键盘焦点且支持 TextPattern,才算能打字。
function Describe-El($e) {
    $o = [ordered]@{ name = ''; type = ''; cls = ''; win = ''; focus = $false; editable = $false }
    try { $o.name = $e.Current.Name } catch { }
    try { $o.type = $e.Current.ControlType.ProgrammaticName.Replace('ControlType.', '') } catch { }
    try { $o.cls = $e.Current.ClassName } catch { }
    try { $o.focus = $e.Current.HasKeyboardFocus } catch { }

    # 往上找顶层窗口名 —— 多往上走几层都行,但要有上限:
    # 每一次都是一趟跨进程 COM 调用,而这是**每次点击**都会走的路。
    try {
        $cur = $e
        for ($i = 0; $i -lt 8; $i++) {
            $p = [System.Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($cur)
            if ($null -eq $p) { break }
            $cur = $p
            $pt = ''
            try { $pt = $cur.Current.ControlType.ProgrammaticName } catch { }
            if ($pt -eq 'ControlType.Window') {
                try { $o.win = $cur.Current.Name } catch { }
                break
            }
        }
    } catch { }

    $ed = $false
    # 1) 能读写值 → 这就是输入框。IsReadOnly 那道判断不能省(见上面计算器那条)。
    try {
        $vp = $e.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
        if ($null -ne $vp -and -not $vp.Current.IsReadOnly) { $ed = $true }
    } catch { }
    # 2) 类型名就叫 Edit(覆盖不支持 ValuePattern 的少数编辑器)
    if (-not $ed -and $o.type -eq 'Edit') { $ed = $true }
    # 3) 有焦点 + 支持 TextPattern → 浏览器/编辑器的正文
    #    ★ 这一条**必须绑在「有焦点」上**:TextPattern 太宽,整个文档、
    #      整个窗口都支持它。不绑的话,点网页里任何一处都会弹键盘。
    if (-not $ed -and $o.focus) {
        try {
            $tp = $e.GetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern)
            if ($null -ne $tp) { $ed = $true }
        } catch { }
    }
    $o.editable = $ed
    return $o
}

# ── OCR:Windows 自带的识字能力,补在 UIA 和「眼」之间 ────────────────────
#
# 为什么需要:微信是 Qt/DirectUI 自绘,**整个窗口 UIA 只暴露 1 个后代元素** ——
# 按名字找控件那条路在它面前是瞎的,只能落到手机上那个 220 秒的视觉模型。
# 但字是**画在屏幕上**的,不管谁画的,OCR 都认。实测全屏一次 0.5 秒。
#
# 小字要放大:13px 的菜单栏(「文件 编辑」那行)原生尺寸认不动,18px 正文
# 中文几乎全对。所以先放大 2× 再 OCR,拿到的框再 ÷2 换回真实屏幕像素。
#
# 懒加载:WinRT 那几个程序集只在第一次真要用 OCR 时才加载 —— 平时纯 UIA
# 的用户不该为它付启动开销。

$script:OCR_SCALE = 2
$script:OcrEngine = $null
$script:AsTaskGeneric = $null
$script:OcrInitErr = $null

function Initialize-Ocr {
    if ($null -ne $script:OcrEngine) { return $true }
    if ($null -ne $script:OcrInitErr) { return $false }
    try {
        Add-Type -AssemblyName System.Drawing, System.Windows.Forms, System.Runtime.WindowsRuntime
        [void][Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
        [void][Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
        [void][Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
        [void][Windows.Globalization.Language, Windows.Globalization, ContentType = WindowsRuntime]

        # PS 5.1 等不了 WinRT 的 IAsyncOperation,得借 System.Runtime.WindowsRuntime
        # 的 AsTask 把它转成 .NET Task(泛型方法要反射 MakeGenericMethod 出来)。
        $script:AsTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
            $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
            $_.GetParameters()[0].ParameterType.Name -like 'IAsyncOperation*'
        })[0]
        if ($null -eq $script:AsTaskGeneric) { throw "找不到 AsTask 泛型方法" }

        $eng = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage(
                   (New-Object Windows.Globalization.Language 'zh-Hans-CN'))
        if ($null -eq $eng) { $eng = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages() }
        if ($null -eq $eng) { throw "系统里没装任何 OCR 语言包" }
        $script:OcrEngine = $eng
        return $true
    } catch {
        $script:OcrInitErr = $_.Exception.Message
        return $false
    }
}

function Await-Op($op, $t) {
    $m = $script:AsTaskGeneric.MakeGenericMethod($t)
    $task = $m.Invoke($null, @($op))
    $task.Wait(-1) | Out-Null
    return $task.Result
}

# 截全屏 → 放大 → OCR → 收回真实像素坐标的文本行
function Get-OcrLines {
    $r = [System.Windows.Forms.SystemInformation]::VirtualScreen

    $bmp = New-Object System.Drawing.Bitmap($r.Width, $r.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($r.Left, $r.Top, 0, 0, $bmp.Size)
    $g.Dispose()

    $s = $script:OCR_SCALE
    $big = New-Object System.Drawing.Bitmap(($r.Width * $s), ($r.Height * $s))
    $g2 = [System.Drawing.Graphics]::FromImage($big)
    $g2.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g2.DrawImage($bmp, 0, 0, ($r.Width * $s), ($r.Height * $s))
    $g2.Dispose(); $bmp.Dispose()

    # ★★ 整屏截图**用完就必须没了** —— 2026-10-06。
    #
    # 这一段原先把整屏 PNG 存到 %TEMP%\ruoxi-uia-ocr.png 之后就再也不管了
    # (实测在盘上留了 1.4MB),而且**「打字之后验货的兜底」也走这条路** ——
    # 也就是说 **打一次字就可能顺带把你的整屏留在盘上**。
    #
    # 它在盘上留的是**你的整个屏幕**,比日志里任何一条都敏感。所以从这一版起两件事:
    #   ① **开头先扫一遍** —— 万一上一次是被硬杀的(见 ②),这一具由这次读屏收走;
    #   ② **try/finally 兜住** —— 正常返回也好、WinRT 那几步中途抛异常也好,
    #      离开这个函数那一刻文件**已经不在了**。
    #
    # ★ 说清楚它**兜不住**什么:进程被强杀在 `Save` 和 `finally` 之间,那一屏就留下了。
    #   这就是 ① 存在的全部理由 —— 它把这个窗口从「永久」缩成「到下一次读屏为止」。
    #   (同 `_log_line` 那条道理:闸要装在**写下去的那一刻**;已经落盘的字节事后抹不掉,
    #    所以这里只能收窄窗口,不能说「稳了」。)
    $path = Join-Path $env:TEMP 'ruoxi-uia-ocr.png'
    Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue

    try {
        $big.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $big.Dispose()

        $file    = Await-Op ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
        $stream  = Await-Op ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
        $decoder = Await-Op ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
        $sb      = Await-Op ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
        $res     = Await-Op ($script:OcrEngine.RecognizeAsync($sb)) ([Windows.Media.Ocr.OcrResult])
        # ★ 先把读句柄关掉再删 —— WinRT 那个 stream 开着的时候文件删不掉,
        #   而 `Remove-Item` 是 SilentlyContinue 的,删不掉**不会吭声**。
        #   (测试钉的就是这一条:跑完读屏,那个文件必须真的不在了。)
        $stream.Dispose()
    } finally {
        Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
    }

    # ⚠️ $res.Lines 是 WinRT 的 IReadOnlyList。PowerShell 5.1 的成员枚举会把
    #    `.Count` 变成「逐个元素取 Count」,遍历也会跟着崩 —— **必须先 @() 包一层**。
    $out = @()
    foreach ($ln in @($res.Lines)) {
        $words = @($ln.Words)
        if ($words.Count -eq 0) { continue }
        $minX = [double]::MaxValue; $minY = [double]::MaxValue
        $maxX = [double]::MinValue; $maxY = [double]::MinValue
        foreach ($w in $words) {
            $bb = $w.BoundingRect
            if ($bb.X -lt $minX) { $minX = $bb.X }
            if ($bb.Y -lt $minY) { $minY = $bb.Y }
            if (($bb.X + $bb.Width) -gt $maxX)  { $maxX = $bb.X + $bb.Width }
            if (($bb.Y + $bb.Height) -gt $maxY) { $maxY = $bb.Y + $bb.Height }
        }
        $k = 1.0 / $s
        $x = [int]($r.Left + $minX * $k)
        $y = [int]($r.Top + $minY * $k)
        $w2 = [int](($maxX - $minX) * $k)
        $h2 = [int](($maxY - $minY) * $k)
        if ($w2 -le 0 -or $h2 -le 0) { continue }
        $out += [ordered]@{
            name = $ln.Text; type = 'Text'
            x = $x; y = $y; w = $w2; h = $h2
            cx = [int]($x + $w2 / 2); cy = [int]($y + $h2 / 2)
        }
    }
    return ,$out
}

# 子进程自己吞掉任何非协议输出,别让它污染 stdout。
$WarningPreference = 'SilentlyContinue'
$ProgressPreference = 'SilentlyContinue'

# ── 主循环 ──────────────────────────────────────────────────────────────
while ($true) {
    $line = [Console]::In.ReadLine()
    if ($null -eq $line) { break }          # stdin 关了 = 该退出了
    if ($line.Trim() -eq '') { continue }

    try {
        $req  = $line | ConvertFrom-Json
        $cmd  = $req.cmd
        $want = $req.name
        $ctype = $req.type
        $limit = if ($req.limit) { [int]$req.limit } else { 40 }

        if ($cmd -eq 'ping') { Reply @{ ok = $true; pong = $true }; continue }
        if ($cmd -eq 'quit') { break }

        if ($cmd -eq 'ocr') {
            if (-not (Initialize-Ocr)) {
                Reply @{ ok = $false; error = "OCR 用不了:$($script:OcrInitErr)" }
                continue
            }
            try {
                # 注意别写 @(Get-OcrLines):Get-OcrLines 里 `return ,$out` 已经
                # 保证「整个数组当一个对象返回」,再 @() 包一层就成了嵌套数组
                # —— 表现为 count=1 且里面不是元素(实测踩到)。
                $lines = Get-OcrLines
                # 指定了窗口就只在它那块矩形里取 —— OCR 是全屏的,不框住的话
                # 「点微信的发送」可能点到后面浏览器里的「发送」。
                $wname = ''
                if ($req.window) {
                    $w = Get-TopWindow $req.window
                    if ($null -eq $w) {
                        Reply @{ ok = $false; error = "没有找到窗口「$($req.window)」" }
                        continue
                    }
                    $wr = Get-Rect $w
                    if ($null -ne $wr) {
                        $x1 = $wr.x; $y1 = $wr.y
                        $x2 = $wr.x + $wr.w; $y2 = $wr.y + $wr.h
                        $lines = @($lines | Where-Object {
                            $_.cx -ge $x1 -and $_.cx -le $x2 -and $_.cy -ge $y1 -and $_.cy -le $y2 })
                    }
                    $wname = $w.Current.Name
                }
                $lim = if ($req.limit) { [int]$req.limit } else { 200 }
                Reply @{ ok = $true; window = $wname; count = $lines.Count
                         lines = @($lines | Select-Object -First $lim) }
            } catch {
                Reply @{ ok = $false; error = "OCR 失败:$($_.Exception.Message)" }
            }
            continue
        }

        if ($cmd -eq 'find') {
            # allowOff=$true —— 这是本次的核心改动。模型问的是「**有没有** X」,
            # 不是「屏幕上看不看得见 X」。VS Code 那种窗口 94% 的可点控件是离屏的,
            # 以前被 IsOffscreen 一刀切掉,于是「明明在,却说没有」。
            $found = Find-Scoped $want $ctype $limit $req.window $true $true
            if ($null -eq $found) {
                Reply @{ ok = $false; error = "没有找到窗口「$($req.window)」" }; continue
            }
            $pub = @($found.hits | Select-Object -First $limit | ForEach-Object { Public-El $_ })
            # desk=$true 表示这批命中**不是从前台窗口里来的**,而是从「其余的桌面窗口」
            # 那一遍来的。上层必须据此判断「这是不是我要操作的那个应用」——
            # 2026-10-03 的误点就是栽在没区分这个上。
            Reply @{ ok = $true; window = $found.nm; desk = $found.desk
                     count = $found.hits.Count; elements = $pub }
            continue
        }

        # patterns:只读诊断。报出目标支持哪些 UIA Pattern、包围盒是不是空的 ——
        # 用来回答「离屏控件到底能不能直接命令它动作」,**不产生任何副作用**。
        if ($cmd -eq 'patterns') {
            $found = Find-Scoped $want '' 5 $req.window $true $true
            if ($null -eq $found) {
                Reply @{ ok = $false; error = "没有找到窗口「$($req.window)」" }; continue
            }
            $rows = @()
            foreach ($hit in $found.hits) {
                $pats = @()
                try {
                    foreach ($p in $hit._el.GetSupportedPatterns()) {
                        $pats += $p.ProgrammaticName.Replace('PatternIdentifiers.Pattern', '')
                    }
                } catch { }
                # 真实包围盒**在这里**读,而不是在 find 里 —— find 走热路径,
                # 几千个元素,每个多读一次跨进程属性就是好几秒;这里只有几个。
                # 而且只有这条路才看得到离屏元素的「虚拟滚动空间」坐标
                # (实测 y=-64728 那种),find 里它们一律是 0。
                $rect = ''
                try {
                    $b = $hit._el.Current.BoundingRectangle
                    $rect = "{0},{1} {2}x{3}" -f [int]$b.X, [int]$b.Y, [int]$b.Width, [int]$b.Height
                } catch { $rect = 'ERR' }
                $rows += [ordered]@{
                    name = $hit.name; type = $hit.type; offscreen = $hit.offscreen
                    rect = $rect
                    patterns = ($pats -join ',')
                }
            }
            Reply @{ ok = $true; window = $found.nm; count = $rows.Count; hits = $rows }
            continue
        }

        # invoke:让**控件自己动作**,而不是点像素。
        #
        # 这是「知道优先」真正的样子:有控件树的时候,根本不必知道它在哪。
        # 对离屏控件更是唯一可行的路 —— 它没画出来,包围盒是空的,拿坐标去点
        # 只会点到 (0,0) 或者别的什么东西上。
        if ($cmd -eq 'invoke') {
            $found = Find-Scoped $want '' 8 $req.window $true $true
            if ($null -eq $found) {
                Reply @{ ok = $false; error = "没有找到窗口「$($req.window)」" }; continue
            }
            if ($found.hits.Count -eq 0) {
                Reply @{ ok = $false; error = "没有叫「$want」的控件" }; continue
            }
            $hit = $found.hits[0]
            $el = $hit._el
            $did = ''

            # 1) 首选:直接把命令发给控件 —— 不碰鼠标、不滚动、不需要看见。
            foreach ($pair in @(
                    @{ P = [System.Windows.Automation.InvokePattern]::Pattern;         M = 'Invoke' },
                    @{ P = [System.Windows.Automation.SelectionItemPattern]::Pattern;  M = 'Select' },
                    @{ P = [System.Windows.Automation.TogglePattern]::Pattern;         M = 'Toggle' },
                    @{ P = [System.Windows.Automation.ExpandCollapsePattern]::Pattern; M = 'Expand' })) {
                $obj = $null
                try { $obj = $el.GetCurrentPattern($pair.P) } catch { $obj = $null }
                if ($null -eq $obj) { continue }
                try {
                    # PS 5.1 里 `$obj.$var()` 这种动态方法调用不可靠,走反射。
                    $mi = $obj.GetType().GetMethod($pair.M)
                    if ($null -ne $mi) { $mi.Invoke($obj, @()) | Out-Null; $did = $pair.M }
                } catch { }
                if ($did) { break }
            }

            if ($did) {
                Reply @{ ok = $true; action = $did; name = $hit.name; type = $hit.type
                         window = $found.nm; desk = $found.desk; offscreen = $hit.offscreen }
                continue
            }

            # 2) 控件不接受命令:让 UIA 自己把它**滚进视野**,再把真实坐标交回
            #    Python 去点。比自己猜「该滚几屏」靠谱得多 —— 滚动由控件自己算。
            try {
                $sp = $el.GetCurrentPattern([System.Windows.Automation.ScrollItemPattern]::Pattern)
                if ($null -ne $sp) {
                    $sp.ScrollIntoView()
                    Start-Sleep -Milliseconds 200
                    $r2 = Get-Rect $el
                    if ($null -ne $r2) {
                        Reply @{ ok = $true; action = 'ScrollIntoView'; need_click = $true
                                 name = $hit.name; type = $hit.type; window = $found.nm
                                 desk = $found.desk
                                 x = $r2.x; y = $r2.y; w = $r2.w; h = $r2.h
                                 cx = $r2.cx; cy = $r2.cy }
                        continue
                    }
                }
            } catch { }

            Reply @{ ok = $false; error = "控件「$($hit.name)」不接受命令,也没法滚进视野"
                     name = $hit.name; type = $hit.type }
            continue
        }

        # setvalue:把一个**值**直接写进控件(ValuePattern),不点、不打字、不管焦点。
        #
        # ★ 它和 type 的区别是「跨应用读写」这件事的另一半:
        #   type 是往**前台那个焦点框**里敲字,焦点一跑就敲进别处;
        #   setvalue 是**指名道姓**往某个控件里写,前台是谁都不影响。
        #   反过来,它只对支持 ValuePattern 且**不是只读**的控件有效 ——
        #   不支持就如实回错,让上层退回 click_ui + type,别硬来。
        #
        # ★ 写完当场**读回来比一次**(回执 verified)。这个项目的规矩:
        #   「发出去了」不许当成「打进去了」—— SetValue 不抛异常只说明它收下了。
        if ($cmd -eq 'setvalue') {
            $val = [string]$req.value
            $found = Find-Scoped $want '' 8 $req.window $true $true
            if ($null -eq $found) {
                Reply @{ ok = $false; error = "没有找到窗口「$($req.window)」" }; continue
            }
            if ($found.hits.Count -eq 0) {
                Reply @{ ok = $false; error = "没有叫「$want」的控件" }; continue
            }
            $tried = 0
            $roHit = $null
            $done = $false
            foreach ($hit in $found.hits) {
                $el = $hit._el
                $vp = $null
                try { $vp = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern) } catch { $vp = $null }
                if ($null -eq $vp) { continue }
                $tried++
                $ro = $true
                try { $ro = [bool]$vp.Current.IsReadOnly } catch { $ro = $true }
                if ($ro) { $roHit = $hit; continue }

                $old = ''
                try { $old = [string]$vp.Current.Value } catch { $old = '' }
                try {
                    # PS 5.1 里动态方法调用不可靠,走反射(和上面 invoke 那段同一个写法)。
                    $mi = $vp.GetType().GetMethod('SetValue')
                    if ($null -eq $mi) { throw '这个控件不支持写入' }
                    $mi.Invoke($vp, @($val)) | Out-Null
                } catch {
                    Reply @{ ok = $false; error = "写入失败:$($_.Exception.Message)"
                             name = $hit.name; type = $hit.type }
                    $done = $true
                    break
                }

                # 验货:再拿一次 ValuePattern 读回来(不缓存,控件可能整个换了对象)
                Start-Sleep -Milliseconds 120
                $now = $null
                try {
                    $vp2 = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                    $now = [string]$vp2.Current.Value
                } catch { $now = $null }
                $verified = 'unknown'
                if ($null -ne $now) { $verified = ($now -eq $val) }
                Reply @{ ok = $true; name = $hit.name; type = $hit.type
                         window = $found.nm; offscreen = $hit.offscreen
                         old = $old; now = $now; verified = $verified }
                $done = $true
                break
            }
            if ($done) { continue }
            if ($null -ne $roHit) {
                Reply @{ ok = $false; error = "控件「$($roHit.name)」是只读的,不能直接写"
                         name = $roHit.name; type = $roHit.type }
                continue
            }
            Reply @{ ok = $false; error = "「$want」不支持直接写入(ValuePattern)。改用 click_ui 点中它,再 type。"
                     tried = $tried }
            continue
        }

        # probe:「屏幕上这一点是不是一个能打字的地方?」
        #
        # 手机端的「点电脑的输入框 → 手机上自动弹输入法」靠这一条。
        # **只读**:不点、不打字、不改任何东西(和 patterns 同一类)。
        #
        # ★★★ 2026-10-04 重写。前两版的两条「实测结论」**都是量错的**,记在这里
        #     免得再走回去 —— 它们错得一模一样:**没确认前台窗口是谁就开测**。
        #
        #  「① FromPoint 够不到网页里的 <input>,只能回 Group」
        #  「② FocusedElement 在浏览器里没有区分度」
        #    —— 那两次测的时候,前台其实是 **VS Code**(`diag.win` 一加上就露馅了)。
        #    窗口都没对,结论自然全是错的。今天把 Edge 真的顶到前台再测:
        #    `list` 明明白白回 `Edit | an input box | 1042,385 476x36`。
        #
        # ★ 今天**真正的**发现是缓存:同一个 Edge 窗口,带 CacheRequest 遍历 65 个
        #   后代里**一个 Edit / Document 都没有**,不带缓存就看得见。Chromium 的
        #   provider 不支持缓存,`Cached.ControlType` 抛异常被静默 catch 掉 ——
        #   症状和「这个应用根本不暴露结构」**长得一模一样**。
        #
        # 判据分三层,从便宜到贵,够用就停:
        #   ① **鼠标指针形状**(Get-CursorKind)—— 一次 API 调用,不管界面谁画的
        #      都有效(微信那种自绘的也认)。这是我们**最信的一条**。
        #   ② FromPoint + 「最小的那个框」的遍历 —— 说得出"点中的是哪个控件",
        #      给回执和诊断用。
        #   ③ 焦点元素 —— 只当补充,不当判据(见下)。
        #
        # editable 的**最终判据 = 指针是 I 型**,不是 UIA。理由见 Get-CursorKind
        # 上面那段:UIA 在浏览器里要绕缓存、在自绘界面里根本没有,而指针形状
        # 是用户自己就看得见的那个信号。UIA 那边算出来的只当佐证与诊断。
        if ($cmd -eq 'probe') {
            $px = [int]$req.x
            $py = [int]$req.y

            # ① 指针形状 —— 一次 API 调用,不管界面谁画的都有效。**判据就是它。**
            $cursor = Get-CursorKind

            # ② 这一点上是个什么控件。带缓存跑一遍;**没结果就不带缓存再跑一遍**
            #    (Chromium 的 provider 不支持缓存,带缓存时网页内容整片消失 ——
            #     症状和「这个应用不暴露结构」一模一样,见上面那段)。
            #    请求里 cache=$false 可以跳过第一遍,给测试用。
            $wantCache = $true
            if ($null -ne $req.cache) { $wantCache = [bool]$req.cache }
            $passes = @($wantCache)
            if ($wantCache) { $passes += $false }

            $hit = $null
            $cands = @()
            $winName = ''
            $seen = 0
            $usedCache = $wantCache
            foreach ($useCache in $passes) {
                $seenThis = 0
                $candsThis = @()
                $hitThis = $null
                $token = $null
                try {
                    if ($useCache) {
                        # 缓存是有代价才用的:这一趟要遍历前台窗口的全部后代,
                        # VS Code 那种有几千个,逐个跨进程问要好几秒。
                        $cr = New-Object System.Windows.Automation.CacheRequest
                        $cr.Add($AE::NameProperty)
                        $cr.Add($AE::ControlTypeProperty)
                        $cr.Add($AE::BoundingRectangleProperty)
                        $cr.Add($AE::ClassNameProperty)
                        $token = $cr.Activate()
                    }
                    try {
                        $w = Get-TopWindow ''
                        if ($null -ne $w) {
                            try { $winName = $w.Current.Name } catch { }
                            foreach ($e in $w.FindAll($TS::Descendants, $COND)) {
                                $seenThis++
                                $t = ''
                                try {
                                    if ($useCache) { $t = $e.Cached.ControlType.ProgrammaticName.Replace('ControlType.', '') }
                                    else { $t = $e.Current.ControlType.ProgrammaticName.Replace('ControlType.', '') }
                                } catch { continue }
                                # 只要这两类。Button / Pane / Group 都不是「能打字」的地方。
                                if ($t -ne 'Edit' -and $t -ne 'Document') { continue }
                                $r = $null
                                try {
                                    if ($useCache) { $r = $e.Cached.BoundingRectangle }
                                    else { $r = $e.Current.BoundingRectangle }
                                } catch { continue }
                                if ([double]::IsNaN($r.X) -or $r.Width -le 0 -or $r.Height -le 0) { continue }
                                if ($px -lt $r.X -or $px -gt ($r.X + $r.Width)) { continue }
                                if ($py -lt $r.Y -or $py -gt ($r.Y + $r.Height)) { continue }
                                $area = $r.Width * $r.Height
                                if ($candsThis.Count -lt 6) {
                                    $candsThis += "$t@$([int]$r.X),$([int]$r.Y) $([int]$r.Width)x$([int]$r.Height)"
                                }
                                # **最小面积优先**:<input> 和它外面的 Document 同时包住
                                # 这一点时要里面那个,所以只留面积最小的。
                                if ($null -eq $hitThis -or $area -lt $hitThis.area) {
                                    $nm = ''; $cl = ''
                                    try {
                                        if ($useCache) { $nm = $e.Cached.Name; $cl = $e.Cached.ClassName }
                                        else { $nm = $e.Current.Name; $cl = $e.Current.ClassName }
                                    } catch { }
                                    $hitThis = @{ area = $area; el = $e; type = $t
                                                  name = $nm; cls = $cl
                                                  x = [int]$r.X; y = [int]$r.Y
                                                  w = [int]$r.Width; h = [int]$r.Height }
                                }
                            }
                        }
                    } finally {
                        if ($null -ne $token) { $token.Dispose() }
                    }
                } catch { }
                $seen = $seenThis
                $cands = $candsThis
                $hit = $hitThis
                $usedCache = $useCache
                if ($null -ne $hit) { break }
            }

            $at = $null
            if ($null -ne $hit) {
                # 命中的那个才去问它支持什么 Pattern —— 只有**一个**元素,
                # 跨进程那几次可以忽略。GetSupportedPatterns 不能走缓存,
                # 所以绝不能放进上面那个循环里。
                $pats = @()
                $ed = $false
                $hasValue = $false
                try {
                    foreach ($p in $hit.el.GetSupportedPatterns()) {
                        $pn = $p.ProgrammaticName.Replace('PatternIdentifiers.Pattern', '')
                        $pats += $pn
                        # 能读写值 = 真输入框(且不是只读的)
                        if ($pn -eq 'ValuePattern') {
                            $hasValue = $true
                            try {
                                $vp = $hit.el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                                if ($null -ne $vp -and -not $vp.Current.IsReadOnly) { $ed = $true }
                            } catch { }
                        }
                    }
                } catch { }
                # 类型名就叫 Edit → 兜底。★ 只在**它根本不支持 ValuePattern 时**才用:
                #   计算器的结果框也叫 Edit,但 ValuePattern 会说 IsReadOnly=true,
                #   上面那条已经正确地判成"不能打字"了,这里不能再把它翻回来。
                if (-not $hasValue -and $hit.type -eq 'Edit') { $ed = $true }
                $at = [ordered]@{
                    name = $hit.name; type = $hit.type; cls = $hit.cls
                    x = $hit.x; y = $hit.y; w = $hit.w; h = $hit.h
                    patterns = ($pats -join ',')
                    editable = $ed
                }
            }

            # 焦点元素 —— **只当补充,不当判据**:浏览器里它一律回页面 Document,
            # 没有区分度(拿它当判据 = 在网页上点哪儿都弹键盘)。
            $fo = $null
            try {
                $fe = $AE::FocusedElement
                if ($null -ne $fe) { $fo = Describe-El $fe }
            } catch { }

            # ★★ 最终判据:**指针是 I 型**。
            #    UIA 那两条只当佐证 —— 它们在原生应用里很准,在浏览器里要绕缓存,
            #    在微信那种自绘界面里**根本没有**(整窗只暴露 1 个后代)。
            #    代价说清楚:浏览器里可选中的正文也是 I 型,会**假阳性**。
            #    两个方向代价不对称 —— 误弹键盘按一下返回就没了,漏判则功能整个不成立,
            #    所以这里偏向弹出来。
            # ★★ 焦点那个**绝不能进判据**(2026-10-04 实测踩到):只要窗口里
            #    某处有个输入框拿着焦点,`focus.editable` 就恒为真 ——
            #    于是**在网页上点空白处也弹键盘**(实测:点 (1200,250) 页面空白,
            #    指针是箭头、at 是页面 Document,却因为焦点在 <input> 上判成了 true)。
            #    焦点是**窗口级**的,不是**这一点级**的 —— 它回答不了"你点的是不是输入框"。
            $editable = ($cursor -eq 'text')
            if (-not $editable -and $null -ne $at -and $at.editable) { $editable = $true }

            # diag 那几项是给「键盘没弹出来」用的:看得出是**没找到输入框**,
            # 还是**根本没问到那个窗口**。症状长得一样,没有这几项只能靠猜。
            Reply @{ ok = $true; cursor = $cursor; editable = $editable
                     at = $at; focus = $fo
                     diag = @{ win = $winName; seen = $seen
                               cached = $usedCache
                               cands = ($cands -join ' | ') } }
            continue
        }

        # list:给模型「看看有什么可点」用
        $w = Get-TopWindow $req.window
        if ($null -eq $w) {
            Reply @{ ok = $false; error = "没有找到窗口「$($req.window)」" }
            continue
        }
        $els = @()
        foreach ($e in $w.FindAll($TS::Descendants, $COND)) {
            if ($els.Count -ge $limit) { break }
            $nm = $null
            try { $nm = $e.Current.Name } catch { continue }
            if (-not $nm -or $nm.Length -gt $MAXNAME) { continue }
            # list 仍只收**屏幕内**的可点类型 —— allowOff 不传,保持默认 false。
            # 否则 VS Code 那种窗口一次就是 909 个离屏元素,直接淹掉 4B 的上下文。
            $el = New-El $e $nm 0 $false
            if ($el) { $els += $el }
        }
        Reply @{ ok = $true; window = $w.Current.Name; count = $els.Count; elements = $els }
    }
    catch {
        Reply @{ ok = $false; error = "$($_.Exception.Message)" }
    }
}
