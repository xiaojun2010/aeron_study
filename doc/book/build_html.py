#!/usr/bin/env python3
"""
把 doc/book 目录下所有 .md 文件转换成 HTML。
特点：
  - Mermaid 代码块 (```mermaid) 转成 <pre class="mermaid"> 让 mermaid.js 渲染
  - 生成 index.html 作为总目录
  - 自动加了阅读友好的 CSS + 上一章 / 下一章 导航
  - Mermaid / 高亮 CDN，离线不可用
用法:
  python3 build_html.py
输出:
  doc/book/html/*.html
"""
import os
import re
import shutil
from pathlib import Path

import markdown

BOOK_DIR = Path("/Users/zxj/work/workspace/quant/aeron_study/doc/book")
OUT_DIR = BOOK_DIR / "html"
OUT_DIR.mkdir(exist_ok=True)

# ── HTML 模板 ─────────────────────────────────────────────────────
TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/github-markdown-css@5/github-markdown-light.min.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/highlight.js@11/styles/github.min.css">
<script src="https://cdn.jsdelivr.net/npm/highlight.js@11/lib/index.min.js"></script>
<script type="module">
  import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs";
  mermaid.initialize({{ startOnLoad: true, theme: "default", securityLevel: "loose" }});
</script>
<style>
  body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial,
                 "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", sans-serif;
    max-width: 960px;
    margin: 0 auto;
    padding: 2em 1em 4em;
    background: #fafafa;
    color: #24292f;
  }}
  .markdown-body {{
    background: #ffffff;
    padding: 2.5em 3em;
    border-radius: 10px;
    box-shadow: 0 2px 12px rgba(0,0,0,0.06);
  }}
  .markdown-body pre {{
    background: #f6f8fa !important;
  }}
  .markdown-body pre.mermaid {{
    background: transparent !important;
    text-align: center;
    padding: 1em;
  }}
  nav.chapter-nav {{
    display: flex;
    justify-content: space-between;
    padding: 1em;
    margin-bottom: 1.5em;
    background: #eef2f7;
    border-radius: 8px;
    font-size: 0.95em;
  }}
  nav.chapter-nav a {{
    color: #0969da;
    text-decoration: none;
  }}
  nav.chapter-nav a:hover {{ text-decoration: underline; }}
  nav.chapter-nav .nav-link {{ min-width: 30%; }}
  nav.chapter-nav .nav-link.next {{ text-align: right; }}
  nav.chapter-nav .nav-link.home {{ text-align: center; }}
</style>
</head>
<body>
<article class="markdown-body">
{nav_top}
{content}
{nav_bottom}
</article>
<script>
  // 高亮所有非 mermaid 代码块
  document.querySelectorAll('pre code:not(.language-mermaid)').forEach(el => {{
    if (window.hljs) hljs.highlightElement(el);
  }});
</script>
</body>
</html>
"""


# ── Mermaid 处理（预处理策略）─────────────────────────────────────
# codehilite 会把所有 fenced code 都当成代码块高亮，我们必须在 markdown 转换前
# 就把 ```mermaid ... ``` 段抽出来，用占位符替换，转换完再塞回去。
MERMAID_BLOCK_RE = re.compile(r"^```mermaid\s*\n(.*?)^```\s*$",
                              re.DOTALL | re.MULTILINE)


def extract_mermaid(md_text: str) -> tuple:
    """从 markdown 里抽出所有 mermaid 块，返回 (处理后的 md, 块列表)。"""
    blocks = []

    def repl(m):
        blocks.append(m.group(1))
        return f"\n\n<!--MERMAID_PLACEHOLDER_{len(blocks) - 1}-->\n\n"

    return MERMAID_BLOCK_RE.sub(repl, md_text), blocks


def inject_mermaid(html: str, blocks: list) -> str:
    """把占位符换回 <pre class="mermaid">...</pre>。"""
    for i, code in enumerate(blocks):
        placeholder = f"<!--MERMAID_PLACEHOLDER_{i}-->"
        # markdown 可能会把 HTML 注释包在 <p> 里，容忍一下
        html = html.replace(f"<p>{placeholder}</p>",
                            f'<pre class="mermaid">\n{code}</pre>')
        html = html.replace(placeholder,
                            f'<pre class="mermaid">\n{code}</pre>')
    return html


# ── 章节标题 & 元信息 ────────────────────────────────────────────
def extract_title(md_text: str, fallback: str) -> str:
    """从 markdown 首个 # 标题提取标题"""
    for line in md_text.splitlines():
        line = line.strip()
        if line.startswith("# "):
            return line[2:].strip()
    return fallback


# ── 内部链接 .md → .html ─────────────────────────────────────────
def rewrite_md_links(html: str) -> str:
    """把 href="xxx.md" 改成 href="xxx.html"（相对链接）"""
    return re.sub(r'href="([^"#]+)\.md(#[^"]*)?"',
                  lambda m: f'href="{m.group(1)}.html{m.group(2) or ""}"',
                  html)


# ── 导航条 ───────────────────────────────────────────────────────
def make_nav(idx: int, chapters: list) -> tuple:
    """生成上/下章导航"""
    prev_link = ""
    if idx > 0:
        prev = chapters[idx - 1]
        prev_link = f'<a href="{prev["out_name"]}" class="nav-link">← {prev["title"]}</a>'
    else:
        prev_link = '<span class="nav-link">&nbsp;</span>'

    home_link = '<a href="index.html" class="nav-link home">📖 目录</a>'

    next_link = ""
    if idx < len(chapters) - 1:
        nxt = chapters[idx + 1]
        next_link = f'<a href="{nxt["out_name"]}" class="nav-link next">{nxt["title"]} →</a>'
    else:
        next_link = '<span class="nav-link next">&nbsp;</span>'

    return f'<nav class="chapter-nav">{prev_link}{home_link}{next_link}</nav>'


# ── 主流程 ───────────────────────────────────────────────────────
def main():
    # 收集所有章节（跳过 README，单独处理）
    md_files = sorted(
        [p for p in BOOK_DIR.glob("*.md") if p.name.lower() != "readme.md"]
    )

    chapters = []
    for p in md_files:
        text = p.read_text(encoding="utf-8")
        title = extract_title(text, p.stem)
        chapters.append({
            "path": p,
            "text": text,
            "title": title,
            "out_name": p.stem + ".html",
        })

    # markdown 转换器
    md = markdown.Markdown(
        extensions=[
            "fenced_code",   # ``` 代码块
            "tables",        # 表格
            "toc",           # [TOC]
            "attr_list",     # {: .class }
            "sane_lists",
            "codehilite",    # 代码高亮
        ],
        extension_configs={
            "codehilite": {"guess_lang": False, "css_class": "codehilite"},
        },
    )

    # 生成每章 HTML
    for idx, ch in enumerate(chapters):
        md.reset()
        preprocessed, mermaid_blocks = extract_mermaid(ch["text"])
        html_body = md.convert(preprocessed)
        html_body = inject_mermaid(html_body, mermaid_blocks)
        html_body = rewrite_md_links(html_body)

        nav_top = make_nav(idx, chapters)
        nav_bottom = make_nav(idx, chapters)

        html_full = TEMPLATE.format(
            title=ch["title"],
            content=html_body,
            nav_top=nav_top,
            nav_bottom=nav_bottom,
        )
        out_path = OUT_DIR / ch["out_name"]
        out_path.write_text(html_full, encoding="utf-8")
        print(f"[OK] {ch['out_name']:32s}  ← {ch['path'].name}")

    # 生成 index.html（基于 README.md）
    readme = BOOK_DIR / "README.md"
    if readme.exists():
        readme_text = readme.read_text(encoding="utf-8")
        md.reset()
        preprocessed, mermaid_blocks = extract_mermaid(readme_text)
        readme_html = md.convert(preprocessed)
        readme_html = inject_mermaid(readme_html, mermaid_blocks)
        readme_html = rewrite_md_links(readme_html)

        index_html = TEMPLATE.format(
            title=extract_title(readme_text, "目录"),
            content=readme_html,
            nav_top="",
            nav_bottom="",
        )
        (OUT_DIR / "index.html").write_text(index_html, encoding="utf-8")
        print(f"[OK] {'index.html':32s}  ← README.md")

    # 复制静态资源（图片等），保持相对链接可用
    for ext in ("*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg"):
        for asset in BOOK_DIR.glob(ext):
            shutil.copy2(asset, OUT_DIR / asset.name)
            print(f"[COPY] {asset.name}")

    print(f"\n共生成 {len(chapters) + 1} 个 HTML，输出目录: {OUT_DIR}")


if __name__ == "__main__":
    main()
