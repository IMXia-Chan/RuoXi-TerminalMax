# -*- coding: utf-8 -*-
from docx import Document
from docx.shared import Pt, RGBColor, Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml.ns import qn

doc = Document()
s = doc.sections[0]
s.top_margin = Cm(2.0); s.bottom_margin = Cm(2.0)
s.left_margin = Cm(2.8); s.right_margin = Cm(2.6)

st = doc.styles['Normal']
st.font.name = '黑体'; st.font.size = Pt(14)
st.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')

LINES = [
("各位评委老师，我是夏昌杰，机器人与自动化学院，大一。", 0),
("先说结论：手机里装了个大脑，手伸到别的设备上。", 10),
("它能同时指挥好几台设备：一台开表格，一台传文件，一台调硬件，互不排队。", 10),
("这是一套基于端侧大模型的本地优先多智能体协同与跨设备执行系统，名字叫从漫。", 10),
("为什么要这么干？", 14),
("因为现在所有 AI 都有一个前提：先联网，先上传。", 0),
("可合同、财报、病历，是不能上传的。", 0),
("而且它只会告诉你该点哪里，点还是你自己点。", 0),
("我们做了四件事。", 14),
("第一，多个 agent，同时分别控制不同设备。", 10),
("手机里一个 agent 管电脑，一个管蓝牙设备，一个管 MQTT 设备。", 0),
("各有名字、职责、预算和记忆，谁也不用等谁。", 0),
("公开资料里，我们没检索到手机端做到这一步的产品。", 0),
("市面上的跨设备方案，大脑都在云上或电脑上，手机只是遥控器。", 0),
("第二，安全不是一句承诺，是三道闸。", 10),
("动作先分级：只读绿，写本机黄，对外发红，红的必须你点头。", 0),
("下发前验签、带序号、防重放。", 0),
("做完必须回执，没有回执照样算失败。", 0),
("我们代码里写着一句话：会撒谎的手不是手，是陷阱。", 0),
("第三，快到你感觉不到它在算。", 10),
("模型每次启动都要重算系统提示和工具表。", 0),
("我们把这段算完存进闪存，下次直接读回来。", 0),
("六百八十五秒，变成零点四三秒。", 0),
("你把手机拿起来，它已经在等你了。", 0),
("第四，说出来就能干活。", 10),
("唤醒、断句、识别、合成，全在手机里，音频一个字节都不出去。", 0),
("这些不是 PPT。", 14),
("v3.1.1 已经发布，安装包七十三兆，安卓十一到十七。", 0),
("电脑端是三十七兆的 Windows 服务。", 0),
("源码 Apache-2.0 公开，一千四百多条单元测试。", 0),
("怎么赚钱？", 14),
("个人版分层订阅，零元、三十九、七十九。", 0),
("机构版按项目私有部署，十五到三十万。", 0),
("说实话，现在零收入、零客户、零付费用户。", 0),
("但成本结构和别人不一样：推理在用户手机上跑，用户越多，我们不花钱。", 0),
("最后一句。", 14),
("这个项目要回答的，不是能不能做个更聪明的助手。", 0),
("是：文件根本不能出门的时候，谁来替你动手？", 0),
]

# 标题
p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(4)
r = p.add_run('ConMarn 三分钟路演 · 念稿')
r.font.name = '黑体'; r.font.size = Pt(13); r.bold = True
r.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')
r.font.color.rgb = RGBColor(0x88, 0x88, 0x88)

p = doc.add_paragraph(); p.alignment = WD_ALIGN_PARAGRAPH.CENTER
p.paragraph_format.space_after = Pt(18)
r = p.add_run('631 字　｜　约 2 分 45 秒')
r.font.name = '黑体'; r.font.size = Pt(10.5)
r.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')
r.font.color.rgb = RGBColor(0x99, 0x99, 0x99)

for text, gap in LINES:
    q = doc.add_paragraph()
    q.paragraph_format.space_before = Pt(gap)
    q.paragraph_format.space_after = Pt(0)
    q.paragraph_format.line_spacing = 1.75
    rr = q.add_run(text)
    rr.font.name = '黑体'; rr.font.size = Pt(14)
    rr.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')

# 第二页
doc.add_page_break()
h = doc.add_paragraph(); h.paragraph_format.space_after = Pt(10)
r = h.add_run('上台要点')
r.font.name = '黑体'; r.font.size = Pt(14); r.bold = True
r.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')

TIPS = [
('三处必须放慢、停半秒',
 '①「是：文件根本不能出门的时候，谁来替你动手？」\n②「六百八十五秒，变成零点四三秒。」\n③「公开资料里，我们没检索到手机端做到这一步的产品。」'),
('上台前问自己一句',
 '「同时指挥好几台设备」这一句，你能不能当场演出来？能演就留，不能演就改成「能同时指挥不同的设备」。'),
('被追问 685 秒怎么来的',
 '同一台手机、同一段固定前缀、同一套量化：重算一遍要十一分多钟，从闪存读回来零点四三秒。答得出就答，答不出就说「降低三个数量级」。'),
('三个不能说',
 '「全球首个」→ 说「公开资料里没检索到」。\n「新算法」→ 说「工程组合」。\n「个人版免费」→ 说「分层订阅，免费档、三十九、七十九」。'),
]
for t, b in TIPS:
    p1 = doc.add_paragraph(); p1.paragraph_format.space_before = Pt(12); p1.paragraph_format.space_after = Pt(2)
    r1 = p1.add_run(t); r1.bold = True; r1.font.name='黑体'; r1.font.size=Pt(12)
    r1.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')
    r1.font.color.rgb = RGBColor(0x1F,0x3A,0x5F)
    for ln in b.split('\n'):
        p2 = doc.add_paragraph(); p2.paragraph_format.space_after = Pt(1); p2.paragraph_format.line_spacing = 1.5
        r2 = p2.add_run(ln); r2.font.name='黑体'; r2.font.size=Pt(11)
        r2.element.rPr.rFonts.set(qn('w:eastAsia'), '黑体')
        r2.font.color.rgb = RGBColor(0x33,0x33,0x33)

out = r'C:\Users\27014\Desktop\ConMarn-3分钟路演稿-念稿版.docx'
doc.save(out)
print('OK')
