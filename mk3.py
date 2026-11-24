# -*- coding: utf-8 -*-
from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

doc = Document()
s = doc.sections[0]
s.top_margin = Cm(1.9); s.bottom_margin = Cm(1.9)
s.left_margin = Cm(2.7); s.right_margin = Cm(2.5)

st = doc.styles['Normal']
st.font.name = '黑体'; st.font.size = Pt(13.5)
st.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')

L = """各位评委老师，我是夏昌杰，机器人与自动化学院，大一。
先跟着我做一次操作。
我对手机说：把上周的数据做成周报，发给老板。
这句话不上云。
断句、识别、理解，全在这部手机里完成。
她先把这句话拆开：取数据、算表格、排版、发送。
每一件事带一个编号、一份预算，出门去找自己的手。
取数据的交给电脑，算表格的留给手机自己。
旁边还有蓝牙的、MQTT 的设备在等，各领各的活。
电脑那边开始动手：先读控件树，不行再认字，还不行才看图。
能不看图就不看图，三级成本差一个数量级。
干完必须回来交差：做成没有、花了多久、卡在哪一步。
少一份回执，这件事就算失败。不猜，也不重试。
这就是这套系统：基于端侧大模型的本地优先多智能体协同与跨设备执行系统，名字叫从漫。
手机里几个 agent，各有名字、职责、预算和记忆，同时分别指挥不同的设备。

这不是一台电脑的事。
她的手可以是电脑，是手机自己，是蓝牙设备，是 MQTT 设备，是云端接口。
今天最多三件事同时跑，一件事底下还能再分八路。
你家里有几台设备，她就有几只手。
可现在市面上所有跨设备 AI，都是一台手机遥控一台电脑，大脑还在云上。

可接的手越多，问题越大。
每多一只手，说明书就长一截；越长，它越慢，越容易挑错。
我们把这个长度锁死了：接十只还是一百只，前缀一样长。
然后我们把这段前缀一次算完，存进闪存。
六百八十五秒，变成零点四三秒。
你把手机拿起来，它已经在等你了。

这些不是 PPT。
v3.1.1 已经发布，安装包七十三兆，安卓十一到十七。
电脑端三十七兆，是 Windows 服务。
源码 Apache-2.0 公开，一千四百多条单元测试。

怎么赚钱？
个人版分层订阅，零元、三十九、七十九。机构版私有部署，十五到三十万。
说实话，现在零收入、零客户、零付费用户。
但成本结构不一样：推理在用户手机上跑，用户越多，我们不花钱。

最后一句。
这个项目要回答的，不是能不能做个更聪明的助手。
是：文件根本不能出门的时候，谁来替你动手？"""

p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(2)
r = p.add_run('ConMarn 三分钟路演 · 念稿'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(12)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r.font.color.rgb=RGBColor(0x88,0x88,0x88)
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(16)
r = p.add_run('652 字　｜　约 2 分 50 秒　｜　一条线 → 铺开 → 收成一个点')
r.font.name='黑体'; r.font.size=Pt(9.5); r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')
r.font.color.rgb=RGBColor(0x99,0x99,0x99)

for line in L.split('\n'):
    if not line.strip():
        q = doc.add_paragraph(); q.paragraph_format.space_after = Pt(8)
        continue
    q = doc.add_paragraph()
    q.paragraph_format.space_after = Pt(0)
    q.paragraph_format.line_spacing = 1.7
    rr = q.add_run(line)
    rr.font.name='黑体'; rr.font.size=Pt(13.5)
    rr.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')

doc.add_page_break()
h = doc.add_paragraph(); h.paragraph_format.space_after = Pt(10)
r = h.add_run('上台要点'); r.bold=True; r.font.name='黑体'; r.font.size=Pt(14)
r.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体')

TIPS = [
('三处停半秒', '①「少一份回执，这件事就算失败。」\n②「六百八十五秒，变成零点四三秒。」\n③「是：文件根本不能出门的时候，谁来替你动手？」'),
('开头那句能不能演', '「我对手机说：把上周的数据做成周报，发给老板」——如果能当场演一次，这段就是全稿最强；不能演，就把「我对手机说」改成「假设我对手机说」。'),
('被追问 685 秒', '同一台手机、同一段固定前缀、同一套量化：重算一遍十一分多钟，从闪存读回零点四三秒。答不出就只说「降低三个数量级」。'),
('三个不能说', '「全球首个」→「公开资料里没检索到」。\n「新算法」→「工程组合」。\n「个人版免费」→「分层订阅，免费档、三十九、七十九」。'),
]
for t,b in TIPS:
    p1 = doc.add_paragraph(); p1.paragraph_format.space_before=Pt(12); p1.paragraph_format.space_after=Pt(2)
    r1 = p1.add_run(t); r1.bold=True; r1.font.name='黑体'; r1.font.size=Pt(12)
    r1.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r1.font.color.rgb=RGBColor(0x1F,0x3A,0x5F)
    for ln in b.split('\n'):
        p2 = doc.add_paragraph(); p2.paragraph_format.space_after=Pt(1); p2.paragraph_format.line_spacing=1.5
        r2 = p2.add_run(ln); r2.font.name='黑体'; r2.font.size=Pt(11)
        r2.element.rPr.rFonts.set(qn('w:eastAsia'),'黑体'); r2.font.color.rgb=RGBColor(0x33,0x33,0x33)

out = r'C:\Users\27014\Desktop\ConMarn-3分钟路演稿-线性版.docx'
doc.save(out); print('OK')
