# -*- coding: utf-8 -*-
from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

doc = Document()
s = doc.sections[0]
s.top_margin = Cm(1.7); s.bottom_margin = Cm(1.7)
s.left_margin = Cm(2.6); s.right_margin = Cm(2.4)
st = doc.styles['Normal']
st.font.name = '宋体'; st.font.size = Pt(12.5)
st.element.rPr.rFonts.set(qn('w:eastAsia'), '宋体')

L = """各位评委老师好，我是机器人与自动化学院的夏昌杰，大一。
我的项目 ConMarn，中文名从漫，是一套基于端侧大模型的本地优先多智能体协同与跨设备执行系统。
判断在手机上完成，执行交给局域网内的其他设备，运行不依赖云端。
举个例子：用户说，把上周的数据做成周报，发给领导。
唤醒、识别、理解都在本地，音频不离开设备。
系统把这句话拆成取数据、算表格、排版、发送四项任务。
每项任务带编号、预算和回执要求，分给能执行的设备。
取数据交给电脑，算表格由手机完成，蓝牙和 MQTT 设备也能领任务。
电脑端先读控件树，失败再识别文字，最后才用视觉，成本依次升高。
做完必须回传结果，缺回执即判失败，不猜测，也不静默重试。

现有跨设备 AI 的推理放在云端或电脑端，手机只做遥控。
我们的推理和编排在手机端，多个智能体并发，各自指挥不同设备。
公开资料中未检索到相同形态的产品：手机端多智能体同时分别控制多台设备，这是全球首个。
可接入电脑、手机自身、蓝牙设备、MQTT 设备和云端接口。
当前三件任务并行，单件还能再分八路。

设备越多，能力清单越长，前缀随之增长，推理变慢，也更容易选错。
我们固定了前缀长度：接十台还是一百台，长度不变。这也是最前沿的。
再把前缀的计算结果整段写进闪存，启动时直接还原。
同一台手机、同一段前缀：重算六百八十五秒，还原零点四三秒。

安全挂在环节之间，不另加一层：只读放行，写本机需确认，对外发送逐次授权。
下发前验签并校验序号，结果逐条核对，缺回执即失败。

v3.1.1 已发布，七十三兆，支持安卓十一到十七，电脑端三十七兆。
源码 Apache-2.0 公开，含一千四百多条单元测试。

个人版分层订阅：免费、三十九、七十九；机构版私有部署。
目前零收入、零客户、零付费用户。
推理在用户设备上，用户增长不增加我们的服务端支出。

这个项目要回答的不是能不能做更聪明的助手。
而是：文件不能离开设备的时候，谁来完成这些操作。"""

n = sum(1 for ch in L if '一' <= ch <= '鿿')
print('汉字', n)

p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(2)
r = p.add_run('ConMarn 三分钟路演 · 念稿'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(12)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r.font.color.rgb=RGBColor(0x88,0x88,0x88)
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(14)
r = p.add_run('%d 字　｜　约 2 分 53 秒' % n)
r.font.name='黑体'; r.font.size=Pt(9.5); r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')
r.font.color.rgb=RGBColor(0x99,0x99,0x99)

for line in L.split('\n'):
    if not line.strip():
        q = doc.add_paragraph(); q.paragraph_format.space_after = Pt(10); continue
    q = doc.add_paragraph()
    q.paragraph_format.space_after = Pt(0)
    q.paragraph_format.line_spacing = 1.6
    rr = q.add_run(line)
    rr.font.name='宋体'; rr.font.size=Pt(12.5)
    rr.element.rPr.rFonts.set(qn('w:eastAsia'),'宋体')

doc.add_page_break()
h = doc.add_paragraph(); h.paragraph_format.space_after = Pt(10)
r = h.add_run('上台要点'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(14)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')

TIPS = [
('「全球首个」这句怎么守',
 '限定词四个：手机端、多智能体、同时、分别控制多台设备。\n被问就答：「公开资料里没有检索到同形态的产品；市面上的跨设备 AI，推理都放在云端或电脑端，手机只做遥控。」\n不要说成「全球首个跨设备 AI 系统」——那类产品确实存在。'),
('三处停顿',
 '①「做完必须回传结果，缺回执即判失败。」\n②「重算六百八十五秒，还原零点四三秒。」\n③「而是：文件不能离开设备的时候，谁来完成这些操作。」'),
('被追问 685 秒',
 '同一台手机、同一段固定前缀、同一套量化：重算一遍十一分多钟，从闪存读回零点四三秒。答不出就只说「降低三个数量级」。'),
('三个不能说',
 '「没有竞品」→「公开资料里没检索到同形态」。\n「新算法」→「工程组合」。\n「个人版免费」→「分层订阅，免费、三十九、七十九」。'),
]
for t,b in TIPS:
    p1 = doc.add_paragraph(); p1.paragraph_format.space_before=Pt(11); p1.paragraph_format.space_after=Pt(2)
    r1 = p1.add_run(t); r1.bold=True; r1.font.name='黑体'; r1.font.size=Pt(12)
    r1.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r1.font.color.rgb=RGBColor(0x8B,0x1A,0x1A)
    for ln in b.split('\n'):
        p2 = doc.add_paragraph(); p2.paragraph_format.space_after=Pt(1); p2.paragraph_format.line_spacing=1.45
        r2 = p2.add_run(ln); r2.font.name='黑体'; r2.font.size=Pt(10.5)
        r2.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r2.font.color.rgb=RGBColor(0x33,0x33,0x33)

out = r'C:\Users\27014\Desktop\ConMarn-3分钟路演稿-清晰版.docx'
doc.save(out); print('OK')
