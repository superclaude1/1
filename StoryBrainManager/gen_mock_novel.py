# -*- coding: utf-8 -*-
import os

content = []
for i in range(1, 35):
    content.append(f"第{i}章 测试章节标题{i}")
    content.append(f"“张师兄，这是第{i}章的对话。”林师弟笑着说道。")
    content.append(f"“对，这就是第{i}章的分析内容。”张小凡回答道。")
    content.append(f"本章是第{i}章的旁白描写，用来测试故事大脑的增量分析，检验前15章和后续章节的连续分析是否能正常推进。")
    content.append("\n")

text = "\n".join(content)
out_path = r"C:\Users\wjs\.gemini\antigravity\brain\daf9dfed-40a2-4c2a-96a7-848fa06f7f14\scratch\mock_novel.txt"
with open(out_path, "w", encoding="utf-8") as f:
    f.write(text)

print("Generated mock novel successfully!")
