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
st.font.name = '黑体'; st.font.size = Pt(13)
st.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')

L = """各位评委老师，大家好。
今天我只讲一件事：我们把大模型，从云端，搬进了手机。
ConMarn，中文名从漫，一套基于端侧大模型的本地优先多智能体协同与跨设备执行系统。
判断在手机里，执行在设备上，数据不出门。
现在，我对手机说一句话：把上周的数据做成周报，发给我领导。
唤醒、识别、理解，全部在本机完成。音频，不出手机。
接下来发生的事情，是过去做不到的。
她把这件任务拆成四项：取数据、算表格、排版、发送。
四项任务，四个编号，四份预算，同时出发。
取数据交给电脑，算表格手机自己干，旁边的蓝牙和 MQTT 设备也能一起领活。
一台手机，同时指挥多台设备。
电脑那边开始干活：先读控件树，不行再认字，最后才看图。
做完必须回来交账：做成没有、花了多久、卡在哪。
少一份回执，就算失败。不猜，也不重试。
这件事，公开资料里没有第二家做到。
现有的跨设备 AI，推理在云端，在电脑，手机只是个遥控器。
我们把推理放回了手机。
手机端，多个智能体，同时，分别，控制多台设备，这是全球首个。
难在哪？
接的设备越多，模型要读的说明书就越长，它就越慢，越容易选错。
我们把前缀的长度锁死了。接十台，一百台，长度不变。
然后我们把这段前缀算完，整整一段，写进闪存。
重新算一遍，六百八十五秒。
从闪存读回来，零点四三秒。
三个数量级。这是目前端侧最前沿的一项工程。
安全，我们不靠承诺，靠机制。
动作分三级：只读直接放行，写本机要确认，对外发送逐次授权。
下发之前验签、带序号、防重放；做完必须回执，没有回执就是失败。
这不是 PPT。
v3.1.1 已经发布。七十三兆，安卓十一到十七，电脑端三十七兆。
源码 Apache-2.0，一千四百多条单元测试，全部公开。
个人版免费、三十九、七十九；机构版私有部署。
今天说实话：零收入，零客户，零付费用户。
但有一件事和别人不一样：推理在用户手机上跑，用户越多，我们不花钱。
最后一句。
我们做的不是一个更聪明的助手。
我们做的是：当文件不能出门的时候，替你动手的那一个。"""

BOLD = {'少一份回执，就算失败。不猜，也不重试。',
        '手机端，多个智能体，同时，分别，控制多台设备，这是全球首个。',
        '从闪存读回来，零点四三秒。',
        '三个数量级。这是目前端侧最前沿的一项工程。',
        '这不是 PPT。'}

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
    q.paragraph_format.line_spacing = 1.62
    rr = q.add_run(line)
    rr.font.name='黑体'; rr.font.size=Pt(13)
    rr.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')
    if line in BOLD:
        rr.bold = True
        rr.font.color.rgb = RGBColor(0x8B,0x1A,0x1A)
    if line.startswith('难在哪'):
        q.paragraph_format.space_before = Pt(12)
    if line.startswith('最后一句'):
        q.paragraph_format.space_before = Pt(12)

doc.add_page_break()
h = doc.add_paragraph(); h.paragraph_format.space_after = Pt(10)
r = h.add_run('上台要点'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(14)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')

TIPS = [
('念法', '发布会腔的核心是三样：句子短、每句只推进一点、关键句重复一遍。\n红字那几句要放慢、要停。念到「这是全球首个」时看评委，不要看稿。'),
('「全球首个」被问怎么答', '限定词四个：手机端、多智能体、同时、分别控制多台设备。\n「公开资料里没有检索到同形态的产品；市面上的跨设备 AI，推理都放在云端或电脑端，手机只做遥控。」\n不能说成「全球首个跨设备 AI 系统」。'),
('被追问 685 秒', '同一台手机、同一段固定前缀、同一套量化：重算十一分多钟，读回零点四三秒。答不出就只说「三个数量级」。'),
('三个不能说', '「没有竞品」；「新算法」；「个人版免费」（要说分层订阅）。'),
]
for t,b in TIPS:
    p1 = doc.add_paragraph(); p1.paragraph_format.space_before=Pt(11); p1.paragraph_format.space_after=Pt(2)
    r1 = p1.add_run(t); r1.bold=True; r1.font.name='黑体'; r1.font.size=Pt(12)
    r1.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r1.font.color.rgb=RGBColor(0x1F,0x3A,0x5F)
    for ln in b.split('\n'):
        p2 = doc.add_paragraph(); p2.paragraph_format.space_after=Pt(1); p2.paragraph_format.line_spacing=1.45
        r2 = p2.add_run(ln); r2.font.name='黑体'; r2.font.size=Pt(10.5)
        r2.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r2.font.color.rgb=RGBColor(0x33,0x33,0x33)

out = r'C:\Users\27014\Desktop\ConMarn-3分钟路演稿-发布会版.docx'
doc.save(out); print('OK')
