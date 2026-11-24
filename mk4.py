# -*- coding: utf-8 -*-
from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

doc = Document()
s = doc.sections[0]
s.top_margin = Cm(1.8); s.bottom_margin = Cm(1.8)
s.left_margin = Cm(2.7); s.right_margin = Cm(2.5)
st = doc.styles['Normal']
st.font.name = '黑体'; st.font.size = Pt(13)
st.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')

L = """各位评委老师，我是夏昌杰，机器人与自动化学院，大一。
我对手机说：把上周的数据做成周报，发给老板。
这句话不上云。
断句、识别、理解，全在手机里。
她先把这句话拆开：取数据、算表格、排版、发送。
每一件事带一个编号、一份预算，出门去找自己的手。
取数据的交给电脑，算表格的留给手机自己。
旁边还有蓝牙和 MQTT 设备在等。
电脑那边开始动手：先读控件树，不行再认字，还不行才看图。
干完必须回来交差：做成没有、卡在哪一步。
少一份回执，这件事就算失败。不猜，也不重试。
这就是这套系统：基于端侧大模型的本地优先多智能体协同与跨设备执行系统，名字叫从漫。
手机里几个 agent，各有名字、职责、预算和记忆，同时分别指挥不同的设备。
全球首个在手机上让多个 agent 同时分别控制多台设备的系统，就是它。
这不是一台电脑的事。
她的手可以是电脑、手机自己、蓝牙设备、MQTT 设备、云端接口。
今天最多三件事同时跑，一件事底下还能再分八路。
你家里有几台设备，她就有几只手。
可现在市面上所有跨设备 AI，都是一台手机遥控一台电脑，大脑还在云上。
安全上我们做得最狠：动作分三级，红的必须你点头；下发验签；做完必须回执，没有回执就算失败。
可接的手越多，问题越大。
每多一只手，说明书就长一截；越长，它越慢，越容易挑错。
我们把这个长度锁死了：接十只还是一百只，前缀一样长。
然后我们把这段前缀一次算完，存进闪存。
这是我们最前沿的一件东西。
六百八十五秒，变成零点四三秒。
你把手机拿起来，它已经在等你了。
这些不是 PPT。
v3.1.1 已发布，安装包七十三兆。
电脑端三十七兆。
源码 Apache-2.0 公开，一千四百多条单元测试。
怎么赚钱？
个人版分层订阅，零元、三十九、七十九；机构版按项目报价。
零收入、零客户、零付费用户。
但成本结构不一样：推理在用户手机上跑，用户越多，我们不花钱。
最后一句。
这个项目要回答的，不是能不能做个更聪明的助手。
是：文件根本不能出门的时候，谁来替你动手？"""

n = sum(1 for ch in L if '一' <= ch <= '鿿')
print('汉字', n)

p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(2)
r = p.add_run('ConMarn 三分钟路演 · 念稿'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(12)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r.font.color.rgb=RGBColor(0x88,0x88,0x88)
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(15)
r = p.add_run('%d 字　｜　约 2 分 52 秒' % n)
r.font.name='黑体'; r.font.size=Pt(9.5); r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')
r.font.color.rgb=RGBColor(0x99,0x99,0x99)

for line in L.split('\n'):
    if not line.strip():
        q = doc.add_paragraph(); q.paragraph_format.space_after = Pt(6); continue
    q = doc.add_paragraph()
    q.paragraph_format.space_after = Pt(0)
    q.paragraph_format.line_spacing = 1.62
    rr = q.add_run(line)
    rr.font.name='黑体'; rr.font.size=Pt(13)
    rr.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')

doc.add_page_break()
h = doc.add_paragraph(); h.paragraph_format.space_after = Pt(10)
r = h.add_run('上台要点'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(14)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')

TIPS = [
('「全球首个」这句怎么守',
 '被问「凭什么说是全球首个」，一句话答：\n「限定词是四个——手机端、多 agent、同时、分别控制多台设备。公开资料里我们没有检索到同形态的产品；市面上的跨设备 AI，大脑都在云上或电脑上，手机只是遥控器。」\n不要说「全球首个跨设备 AI 系统」——那类产品有，会被一句话推翻。'),
('三处停半秒',
 '①「少一份回执，这件事就算失败。」\n②「六百八十五秒，变成零点四三秒。」\n③「是：文件根本不能出门的时候，谁来替你动手？」'),
('被追问 685 秒',
 '同一台手机、同一段固定前缀、同一套量化：重算一遍十一分多钟，从闪存读回零点四三秒。答不出就只说「降低三个数量级」。'),
('三个不能说',
 '「没有竞品」→「公开资料里没检索到同形态」。\n「新算法」→「工程组合」。\n「个人版免费」→「分层订阅，免费档、三十九、七十九」。'),
]
for t,b in TIPS:
    p1 = doc.add_paragraph(); p1.paragraph_format.space_before=Pt(11); p1.paragraph_format.space_after=Pt(2)
    r1 = p1.add_run(t); r1.bold=True; r1.font.name='黑体'; r1.font.size=Pt(12)
    r1.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r1.font.color.rgb=RGBColor(0x8B,0x1A,0x1A)
    for ln in b.split('\n'):
        p2 = doc.add_paragraph(); p2.paragraph_format.space_after=Pt(1); p2.paragraph_format.line_spacing=1.45
        r2 = p2.add_run(ln); r2.font.name='黑体'; r2.font.size=Pt(10.5)
        r2.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r2.font.color.rgb=RGBColor(0x33,0x33,0x33)

out = r'C:\Users\27014\Desktop\ConMarn-3分钟路演稿-定稿.docx'
doc.save(out); print('OK')
