# -*- coding: utf-8 -*-
"""从 AiAgent.kt 里**抠出**真实的 SYSTEM_PROMPT 和 TOOL_SCHEMA。

为什么要抠,而不是在这儿再抄一份:

    评测的全部价值在于「改了 App 的提示词,命中率是涨是跌」。如果这份 Python
    里另存一份手抄的 schema,改了 Kotlin 却忘了同步,评测就会拿旧 schema 去测,
    报告一片绿 —— 比没有评测更糟,因为它会给你虚假的信心。

所以**唯一真源永远是 AiAgent.kt**,这里只做提取。

TOOL_SCHEMA 那段 Kotlin 是用 `fn(...)` / `p(...)` 的小 DSL 写的,而它的参数表达式
恰好也是合法的 Python(`JSONObject().put(k,v)` 链式返回自身)。所以不需要写解析器 ——
把括号配平切出来,套一层同名 shim 直接 `eval` 就行。
"""
import os
import re

AI_AGENT_KT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "java", "com", "example", "touchpad", "AiAgent.kt")

SYSTEM_PROMPT_RE = re.compile(r'SYSTEM_PROMPT = """(.*?)"""', re.S)
SCHEMA_BLOCK_RE = re.compile(r'TOOL_SCHEMA = JSONArray\(\)\.apply\s*\{', re.S)


# --------------------------------------------------------------------------
# 括号配平扫描(必须认字符串,描述里全是中文括号和逗号)
# --------------------------------------------------------------------------
def _skip_string(s, i):
    """s[i] 是引号,返回闭引号之后的下标。"""
    i += 1
    while i < len(s):
        if s[i] == "\\":
            i += 2
            continue
        if s[i] == '"':
            return i + 1
        i += 1
    raise ValueError("字符串没闭合")


def _split_args(s):
    """把 `a, b(c, d), e` 切成 ['a', 'b(c, d)', 'e']。"""
    out, depth, start, i = [], 0, 0, 0
    while i < len(s):
        c = s[i]
        if c == '"':
            i = _skip_string(s, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            out.append(s[start:i].strip())
            start = i + 1
        i += 1
    tail = s[start:].strip()
    if tail:
        out.append(tail)
    return out


def _find_calls(block, name):
    """取出 block 里所有 `name(...)` 的实参串(跳过 Kotlin 的 `fun name(` 定义)。"""
    calls, i, needle = [], 0, name + "("
    while True:
        j = block.find(needle, i)
        if j < 0:
            return calls
        if block[max(0, j - 4):j] == "fun ":      # 定义处,不是调用
            i = j + len(needle)
            continue
        depth, k = 0, j + len(needle) - 1
        while k < len(block):                     # 配平到自己的闭括号
            c = block[k]
            if c == '"':
                k = _skip_string(block, k)
                continue
            if c in "([{":
                depth += 1
            elif c in ")]}":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        calls.append(block[j + len(needle):k])
        i = k + 1


# --------------------------------------------------------------------------
# 跑 Kotlin 表达式的 Python 替身
# --------------------------------------------------------------------------
class _Obj(dict):
    def put(self, k, v):        # Kotlin JSONObject.put 链式返回自身
        self[k] = v
        return self


def _namespace():
    return {
        "JSONObject": _Obj,
        "JSONArray": lambda x=None: list(x) if x is not None else [],
        "listOf": lambda *a: list(a),
        "emptyList": lambda: [],
        "p": lambda desc: _Obj(type="string", description=desc),
    }


def load(kt_path=AI_AGENT_KT):
    """→ (system_prompt, tool_schema)。tool_schema 是 OpenAI 格式的 list[dict]。"""
    with open(kt_path, encoding="utf-8") as fp:
        src = fp.read()

    m = SYSTEM_PROMPT_RE.search(src)
    if not m:
        raise SystemExit("在 %s 里没找到 SYSTEM_PROMPT" % kt_path)
    system_prompt = m.group(1)

    m = SCHEMA_BLOCK_RE.search(src)
    if not m:
        raise SystemExit("在 %s 里没找到 TOOL_SCHEMA" % kt_path)
    block = src[m.end():]
    end = block.find("\n}")
    blocked = block[:end if end > 0 else len(block)]

    schema = []
    for raw in _find_calls(blocked, "fn"):
        parts = _split_args(raw)
        if len(parts) != 4:
            raise SystemExit("fn(...) 参数不是 4 个,抠不动了: %r" % raw[:120])
        # 外层的括号是**必须的**:Kotlin 允许 `"前半" +\n"后半"` 这样跨行拼字符串,
        # 而 Python 在 eval 里遇到裸表达式的换行就断句了,报 invalid syntax。
        name, desc, props, required = (eval("(" + p + ")", _namespace()) for p in parts)
        schema.append({"type": "function", "function": {
            "name": name, "description": desc,
            "parameters": {"type": "object", "properties": props,
                           "required": required}}})
    if not schema:
        raise SystemExit("TOOL_SCHEMA 抠出来是空的,提取逻辑坏了")
    return system_prompt, schema


if __name__ == "__main__":
    import json
    sp, tools = load()
    print("SYSTEM_PROMPT %d 字符" % len(sp))
    print("工具 %d 个: %s" % (len(tools), [t["function"]["name"] for t in tools]))
    print(json.dumps(tools[-1], ensure_ascii=False, indent=2))
