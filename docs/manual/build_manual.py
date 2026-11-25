#!/usr/bin/env python3
"""
构建图文并茂的《12 路锁控板测试配置 App 用户手册》PDF。
依赖：
- docs/manual/screenshots/*.png（由 ManualScreenshotCaptureTest 采集）
- /usr/bin/google-chrome（headless 打印为 A4 PDF）
"""

from __future__ import annotations

import base64
import html
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANUAL_DIR = Path(__file__).resolve().parent
SCREENSHOTS_DIR = MANUAL_DIR / "screenshots"
HTML_PATH = MANUAL_DIR / "manual.html"
PDF_PATH = ROOT / "docs" / "用户手册.pdf"
CHROME_BIN = Path("/usr/bin/google-chrome")

EXPECTED_SCREENSHOTS = [
    "01_connect_scan.png",
    "02_settings_profile.png",
    "03_settings_dip.png",
    "04_control_top.png",
    "05_control_channels.png",
    "06_control_batch.png",
    "07_test_setup.png",
    "08_test_report.png",
    "09_terminal.png",
]


def img_data_uri(filename: str) -> str:
    path = SCREENSHOTS_DIR / filename
    if not path.is_file():
        raise FileNotFoundError(f"缺少截图文件: {path}")
    b64 = base64.b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/png;base64,{b64}"


def svg_bus_topology() -> str:
    return """
<svg viewBox="0 0 760 270" width="100%" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="RS485 总线接线与三种链路拓扑图">
  <defs>
    <marker id="arrB" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,1 L7,4 L0,7 Z" fill="#1565C0"/>
    </marker>
    <marker id="arrO" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,1 L7,4 L0,7 Z" fill="#E65100"/>
    </marker>
  </defs>
  <rect x="2" y="2" width="756" height="266" rx="10" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.2"/>
  <text x="18" y="26" font-size="13" font-weight="700" fill="#0F172A">图 1：Android 设备三种链路接入方式与 RS485 总线级联拓扑（9600 8N1）</text>

  <!-- Android 设备 -->
  <rect x="20" y="48" width="145" height="196" rx="12" fill="#FFFFFF" stroke="#1565C0" stroke-width="2"/>
  <rect x="20" y="48" width="145" height="32" rx="12" fill="#1565C0"/>
  <rect x="20" y="68" width="145" height="12" fill="#1565C0"/>
  <text x="92" y="69" text-anchor="middle" font-size="12" font-weight="700" fill="#FFFFFF">Android 测试终端</text>
  <text x="92" y="96" text-anchor="middle" font-size="10.5" fill="#334155">Android 8.0+ (目标 11)</text>
  <rect x="32" y="108" width="121" height="34" rx="5" fill="#E3F2FD" stroke="#90CAF9"/>
  <text x="92" y="123" text-anchor="middle" font-size="10.5" font-weight="700" fill="#0D47A1">方式 A：USB OTG</text>
  <text x="92" y="136" text-anchor="middle" font-size="9.5" fill="#1E3A8A">CH340 / CP210x / FTDI</text>

  <rect x="32" y="150" width="121" height="34" rx="5" fill="#E0F2F1" stroke="#80CBC4"/>
  <text x="92" y="165" text-anchor="middle" font-size="10.5" font-weight="700" fill="#004D40">方式 B：板载串口</text>
  <text x="92" y="178" text-anchor="middle" font-size="9.5" fill="#00695C">/dev/ttyS* (可选 RTS)</text>

  <rect x="32" y="192" width="121" height="38" rx="5" fill="#FFF3E0" stroke="#FFB74D"/>
  <text x="92" y="207" text-anchor="middle" font-size="10.5" font-weight="700" fill="#E65100">方式 C：TCP 网络</text>
  <text x="92" y="221" text-anchor="middle" font-size="9.5" fill="#9A3412">串口服务器透传 (9600 8N1)</text>

  <!-- 转换器 / 网关（三种接入方式任选其一，均引出 A/B 两条差分线） -->
  <rect x="196" y="54" width="134" height="50" rx="6" fill="#FFFFFF" stroke="#1565C0" stroke-width="1.4"/>
  <text x="263" y="75" text-anchor="middle" font-size="11" font-weight="700" fill="#1565C0">USB 转 485 适配器</text>
  <text x="263" y="91" text-anchor="middle" font-size="9.2" fill="#475569">首次连接需点“允许”授权</text>

  <rect x="196" y="124" width="134" height="48" rx="6" fill="#FFFFFF" stroke="#00796B" stroke-width="1.4"/>
  <text x="263" y="144" text-anchor="middle" font-size="11" font-weight="700" fill="#00796B">主板 RS485 收发器</text>
  <text x="263" y="160" text-anchor="middle" font-size="9.2" fill="#475569">RTS 控向时开内核 RS485</text>

  <rect x="196" y="192" width="134" height="50" rx="6" fill="#FFFFFF" stroke="#E65100" stroke-width="1.4"/>
  <text x="263" y="213" text-anchor="middle" font-size="11" font-weight="700" fill="#E65100">TCP 串口服务器</text>
  <text x="263" y="229" text-anchor="middle" font-size="9.2" fill="#475569">TCP Server 透传 · 勿拆帧</text>

  <path d="M153,125 L192,80" stroke="#1565C0" stroke-width="1.6" fill="none" marker-end="url(#arrB)"/>
  <path d="M153,167 L192,148" stroke="#00796B" stroke-width="1.6" fill="none" marker-end="url(#arrB)"/>
  <path d="M153,211 L192,217" stroke="#E65100" stroke-width="1.6" fill="none" marker-end="url(#arrO)"/>

  <!-- 各自双线接入 RS485 A (x=362) 与 B (x=378) -->
  <!-- USB A/B -->
  <path d="M330,71 L362,71 L362,142" stroke="#D32F2F" stroke-width="2" fill="none"/>
  <path d="M330,87 L378,87 L378,160" stroke="#1565C0" stroke-width="2" fill="none"/>
  <text x="336" y="68" font-size="8.5" font-weight="700" fill="#B91C1C">A</text>
  <text x="336" y="97" font-size="8.5" font-weight="700" fill="#1D4ED8">B</text>
  <!-- 板载串口 A/B -->
  <path d="M330,142 L362,142" stroke="#D32F2F" stroke-width="2" fill="none"/>
  <path d="M330,160 L378,160" stroke="#1565C0" stroke-width="2" fill="none"/>
  <text x="336" y="139" font-size="8.5" font-weight="700" fill="#B91C1C">A</text>
  <text x="336" y="170" font-size="8.5" font-weight="700" fill="#1D4ED8">B</text>
  <!-- TCP 串口服务器 A/B -->
  <path d="M330,208 L362,208 L362,142" stroke="#D32F2F" stroke-width="2" fill="none"/>
  <path d="M330,224 L378,224 L378,160" stroke="#1565C0" stroke-width="2" fill="none"/>
  <text x="336" y="205" font-size="8.5" font-weight="700" fill="#B91C1C">A</text>
  <text x="336" y="234" font-size="8.5" font-weight="700" fill="#1D4ED8">B</text>

  <!-- 汇流节点圆点 -->
  <circle cx="362" cy="142" r="3.2" fill="#D32F2F"/>
  <circle cx="378" cy="160" r="3.2" fill="#1565C0"/>

  <!-- 总线 A / B 双绞线 -->
  <line x1="362" y1="142" x2="736" y2="142" stroke="#D32F2F" stroke-width="3"/>
  <line x1="378" y1="160" x2="736" y2="160" stroke="#1565C0" stroke-width="3"/>
  <rect x="392" y="114" width="98" height="20" rx="4" fill="#FEF2F2" stroke="#FCA5A5"/>
  <text x="441" y="128" text-anchor="middle" font-size="9.5" font-weight="700" fill="#B91C1C">485-A (+) 对应接 A</text>
  <rect x="392" y="168" width="98" height="20" rx="4" fill="#EFF6FF" stroke="#93C5FD"/>
  <text x="441" y="182" text-anchor="middle" font-size="9.5" font-weight="700" fill="#1D4ED8">485-B (-) 对应接 B</text>

  <!-- 锁控板 1 -->
  <line x1="532" y1="142" x2="532" y2="100" stroke="#D32F2F" stroke-width="2"/>
  <line x1="546" y1="160" x2="546" y2="100" stroke="#1565C0" stroke-width="2"/>
  <circle cx="532" cy="142" r="3" fill="#D32F2F"/>
  <circle cx="546" cy="160" r="3" fill="#1565C0"/>
  <rect x="486" y="42" width="112" height="58" rx="6" fill="#FFFFFF" stroke="#334155" stroke-width="1.5"/>
  <text x="542" y="60" text-anchor="middle" font-size="11" font-weight="700" fill="#0F172A">12 路锁控板 #1</text>
  <text x="542" y="75" text-anchor="middle" font-size="9.5" fill="#1565C0">地址 = 1 (不可重复)</text>
  <text x="542" y="90" text-anchor="middle" font-size="9" fill="#64748B">第 1–12 路锁/灯负载</text>

  <!-- 锁控板 2 -->
  <line x1="658" y1="142" x2="658" y2="100" stroke="#D32F2F" stroke-width="2"/>
  <line x1="672" y1="160" x2="672" y2="100" stroke="#1565C0" stroke-width="2"/>
  <circle cx="658" cy="142" r="3" fill="#D32F2F"/>
  <circle cx="672" cy="160" r="3" fill="#1565C0"/>
  <rect x="612" y="42" width="112" height="58" rx="6" fill="#FFFFFF" stroke="#334155" stroke-width="1.5"/>
  <text x="668" y="60" text-anchor="middle" font-size="11" font-weight="700" fill="#0F172A">12 路锁控板 #2</text>
  <text x="668" y="75" text-anchor="middle" font-size="9.5" fill="#1565C0">地址 = 2 (不可重复)</text>
  <text x="668" y="90" text-anchor="middle" font-size="9" fill="#64748B">第 1–12 路锁/灯负载</text>

  <!-- 下方级联说明与注意 -->
  <rect x="498" y="192" width="238" height="54" rx="6" fill="#FFF7ED" stroke="#FDBA74"/>
  <text x="508" y="209" font-size="10" font-weight="700" fill="#9A3412">接线与通信红线（三种链路任选其一）</text>
  <text x="508" y="224" font-size="9.2" fill="#7C2D12">1. 同一总线挂载地址 1–15，严禁地址冲突。</text>
  <text x="508" y="238" font-size="9.2" fill="#7C2D12">2. 必须 A 接 A、B 接 B；接反将扫描不到板卡。</text>
</svg>
"""


def _dip_svg_block(x: int, y: int, title: str, bits_1_to_4: list[bool], valid: bool = True) -> str:
    # bits_1_to_4[i] == True means ON (up)
    border = "#1565C0" if valid else "#C62828"
    bg = "#FFFFFF" if valid else "#FEF2F2"
    parts = [
        f'<rect x="{x}" y="{y}" width="166" height="114" rx="8" fill="{bg}" stroke="{border}" stroke-width="1.4"/>',
        f'<text x="{x + 83}" y="{y + 20}" text-anchor="middle" font-size="11.5" font-weight="700" fill="{border}">{html.escape(title)}</text>',
        f'<rect x="{x + 25}" y="{y + 30}" width="116" height="56" rx="5" fill="#D32F2F"/>',
        f'<text x="{x + 34}" y="{y + 44}" font-size="8.5" font-weight="700" fill="#FFFFFF">ON</text>',
        f'<text x="{x + 32}" y="{y + 78}" font-size="7.5" fill="#FFCDD2">OFF</text>',
    ]
    for i in range(4):
        sx = x + 55 + i * 21
        sy = y + 36
        parts.append(f'<rect x="{sx}" y="{sy}" width="14" height="32" rx="1.5" fill="#B71C1C" stroke="#FFFFFF" stroke-width="1"/>')
        knob_y = sy + 2 if bits_1_to_4[i] else sy + 18
        parts.append(f'<rect x="{sx + 2}" y="{knob_y}" width="10" height="12" rx="1" fill="#FFFFFF"/>')
        parts.append(f'<text x="{sx + 7}" y="{y + 81}" text-anchor="middle" font-size="8.5" font-weight="700" fill="#FFFFFF">{i + 1}</text>')
    desc = " ".join("ON" if b else "OFF" for b in reversed(bits_1_to_4))
    parts.append(f'<text x="{x + 83}" y="{y + 104}" text-anchor="middle" font-size="9.5" fill="#334155">拨码(4321)：{desc}</text>')
    return "\n".join(parts)


def svg_dip_diagram() -> str:
    return f"""
<svg viewBox="0 0 760 185" width="100%" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="4位拨码开关地址换算图">
  <rect x="2" y="2" width="756" height="181" rx="10" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.2"/>
  <text x="18" y="25" font-size="13" font-weight="700" fill="#0F172A">图 2：4 位拨码开关地址换算示意图（地址 = S1×1 + S2×2 + S3×4 + S4×8，ON=1 向上）</text>
  {_dip_svg_block(18, 42, "示例：地址 1 (1+0+0+0)", [True, False, False, False], True)}
  {_dip_svg_block(202, 42, "示例：地址 5 (1+0+4+0)", [True, False, True, False], True)}
  {_dip_svg_block(386, 42, "示例：地址 15 (1+2+4+8)", [True, True, True, True], True)}
  {_dip_svg_block(570, 42, "地址 0：无效 (禁止使用)", [False, False, False, False], False)}
  <text x="18" y="173" font-size="10" fill="#475569">提示：App 内“配置 → 拨码地址换算”支持直接点击拨码块图形实时换算；注意面板印字顺序通常从左到右为 S1→S4，App 文本提示按(4321)列出。</text>
</svg>
"""


def svg_safety_workflow() -> str:
    return """
<svg viewBox="0 0 760 218" width="100%" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="现场标准作业与防烧锁安全闭环流程图">
  <defs>
    <marker id="arrDark" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,1 L7,4 L0,7 Z" fill="#334155"/>
    </marker>
    <marker id="arrRed" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
      <path d="M0,1 L7,4 L0,7 Z" fill="#C62828"/>
    </marker>
  </defs>
  <rect x="2" y="2" width="756" height="214" rx="10" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.2"/>
  <text x="18" y="25" font-size="13" font-weight="700" fill="#0F172A">图 3：现场标准作业与防烧锁安全闭环流程</text>

  <!-- Step 1 -->
  <rect x="18" y="44" width="128" height="74" rx="8" fill="#EFF6FF" stroke="#1565C0" stroke-width="1.6"/>
  <circle cx="34" cy="60" r="9" fill="#1565C0"/><text x="34" y="64" text-anchor="middle" font-size="10" font-weight="700" fill="#FFF">1</text>
  <text x="48" y="64" font-size="11" font-weight="700" fill="#0D47A1">配置接线档案</text>
  <text x="26" y="83" font-size="9.5" fill="#1E3A8A">逐路标明：电控锁 /</text>
  <text x="26" y="96" font-size="9.5" fill="#1E3A8A">灯与继电器 / 未使用</text>
  <text x="26" y="109" font-size="9.5" fill="#1E3A8A">并核实“有反馈”勾选</text>

  <!-- Step 2 -->
  <rect x="166" y="44" width="128" height="74" rx="8" fill="#EFF6FF" stroke="#1565C0" stroke-width="1.6"/>
  <circle cx="182" cy="60" r="9" fill="#1565C0"/><text x="182" y="64" text-anchor="middle" font-size="10" font-weight="700" fill="#FFF">2</text>
  <text x="196" y="64" font-size="11" font-weight="700" fill="#0D47A1">连接并扫描</text>
  <text x="174" y="83" font-size="9.5" fill="#1E3A8A">选 USB/串口/TCP</text>
  <text x="174" y="96" font-size="9.5" fill="#1E3A8A">扫描 1–15 地址，核对</text>
  <text x="174" y="109" font-size="9.5" fill="#1E3A8A">私有 ≥0200 / MB ≥0002</text>

  <!-- Step 3 -->
  <rect x="314" y="44" width="134" height="74" rx="8" fill="#FFF7ED" stroke="#E65100" stroke-width="1.8"/>
  <circle cx="330" cy="60" r="9" fill="#E65100"/><text x="330" y="64" text-anchor="middle" font-size="10" font-weight="700" fill="#FFF">3</text>
  <text x="344" y="64" font-size="11" font-weight="700" fill="#9A3412">确认接线安全门</text>
  <text x="322" y="83" font-size="9.5" fill="#7C2D12">仅开锁可跳过此步；</text>
  <text x="322" y="96" font-size="9.5" fill="#7C2D12">持续/定时通电前必须</text>
  <text x="322" y="109" font-size="9.5" fill="#7C2D12">到现场核对并“确认接线”</text>

  <!-- Step 4 -->
  <rect x="468" y="44" width="128" height="74" rx="8" fill="#E8F5E9" stroke="#2E7D32" stroke-width="1.6"/>
  <circle cx="484" cy="60" r="9" fill="#2E7D32"/><text x="484" y="64" text-anchor="middle" font-size="10" font-weight="700" fill="#FFF">4</text>
  <text x="498" y="64" font-size="11" font-weight="700" fill="#1B5E20">控制 / 自动测试</text>
  <text x="476" y="83" font-size="9.5" fill="#1B5E20">脉冲开锁约 0.35–1.4s</text>
  <text x="476" y="96" font-size="9.5" fill="#1B5E20">灯/继电器可开/关/定时</text>
  <text x="476" y="109" font-size="9.5" fill="#1B5E20">建议勾选人工物理确认</text>

  <!-- Step 5 -->
  <rect x="616" y="44" width="126" height="74" rx="8" fill="#E0F2F1" stroke="#00796B" stroke-width="1.6"/>
  <circle cx="632" cy="60" r="9" fill="#00796B"/><text x="632" y="64" text-anchor="middle" font-size="10" font-weight="700" fill="#FFF">5</text>
  <text x="646" y="64" font-size="11" font-weight="700" fill="#004D40">关断并读回清账</text>
  <text x="624" y="83" font-size="9.5" fill="#004D40">断开/停止测试时自动</text>
  <text x="624" y="96" font-size="9.5" fill="#004D40">逐路关断并读回 0004</text>
  <text x="624" y="109" font-size="9.5" fill="#004D40">确认，防带电残留</text>

  <path d="M146,81 L162,81" stroke="#334155" stroke-width="1.8" marker-end="url(#arrDark)"/>
  <path d="M294,81 L310,81" stroke="#334155" stroke-width="1.8" marker-end="url(#arrDark)"/>
  <path d="M448,81 L464,81" stroke="#334155" stroke-width="1.8" marker-end="url(#arrDark)"/>
  <path d="M596,81 L612,81" stroke="#334155" stroke-width="1.8" marker-end="url(#arrDark)"/>

  <!-- 异常断线分支 -->
  <rect x="166" y="140" width="576" height="58" rx="8" fill="#FEF2F2" stroke="#C62828" stroke-width="1.4" stroke-dasharray="5,3"/>
  <text x="180" y="160" font-size="10.5" font-weight="700" fill="#B91C1C">异常中断分支（如带电期间 USB 适配器意外拔出，App 来不及关断）</text>
  <text x="180" y="176" font-size="9.2" fill="#7F1D1D">• 重连同一适配器（有序列号）→ App 自动带入登记并读回 0004 核对清账；</text>
  <text x="180" y="190" font-size="8.8" fill="#7F1D1D">• 无序列号 USB 适配器 → 连接页提示“遗留输出”，须选“是同一条总线：带入并读回”或现场断电后点“现场已确认断电”。</text>
  <path d="M532,118 L532,136" stroke="#C62828" stroke-width="1.6" stroke-dasharray="3,2" marker-end="url(#arrRed)"/>
</svg>
"""


def svg_channel_safety_matrix() -> str:
    return """
<svg viewBox="0 0 760 172" width="100%" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="通道类型与安全策略红线对照图">
  <rect x="2" y="2" width="756" height="168" rx="10" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1.2"/>
  <text x="18" y="25" font-size="13" font-weight="700" fill="#0F172A">图 4：三类通道接线档案的安全策略与协议级防误触拦截</text>

  <!-- 电控锁 -->
  <rect x="18" y="38" width="234" height="118" rx="8" fill="#EFF6FF" stroke="#1565C0" stroke-width="1.4"/>
  <text x="30" y="58" font-size="12" font-weight="700" fill="#0D47A1">电控锁（默认类型）</text>
  <text x="30" y="76" font-size="9.5" fill="#1B5E20">✔ 允许：短脉冲开锁（约 0.35–1.4 s）</text>
  <text x="30" y="92" font-size="9.5" fill="#1B5E20">✔ 允许：读取门磁/锁反馈（0x11/0x12/02）</text>
  <text x="30" y="108" font-size="9.5" fill="#B91C1C" font-weight="700">✘ 严禁：持续通电（0x16/0x1A/0004）</text>
  <text x="30" y="124" font-size="9.5" fill="#9A3412">⚠ 定时通电：默认禁止；仅可在配置设上限</text>
  <text x="30" y="142" font-size="9" fill="#475569">原因：电控锁线圈持续通电会过热烧毁</text>

  <!-- 灯/继电器 -->
  <rect x="263" y="38" width="234" height="118" rx="8" fill="#E8F5E9" stroke="#2E7D32" stroke-width="1.4"/>
  <text x="275" y="58" font-size="12" font-weight="700" fill="#1B5E20">灯 / 继电器（持续负载）</text>
  <text x="275" y="76" font-size="9.5" fill="#1B5E20">✔ 允许：开/关持续输出与定时通电</text>
  <text x="275" y="92" font-size="9.5" fill="#1B5E20">✔ 允许：整体输出 0x1A / 0004（须已确认）</text>
  <text x="275" y="108" font-size="9.5" fill="#9A3412" font-weight="700">⚠ 前提：每次连接后须点“确认接线”</text>
  <text x="275" y="124" font-size="9.5" fill="#475569">• 修改任何板卡档案都会自动撤销确认</text>
  <text x="275" y="140" font-size="8.8" fill="#475569">• 自动测试 T6 默认跳过；手动 0x14 脉冲全 12 路</text>

  <!-- 未使用 & 协议拦截 -->
  <rect x="508" y="38" width="234" height="118" rx="8" fill="#FEF2F2" stroke="#C62828" stroke-width="1.4"/>
  <text x="520" y="58" font-size="12" font-weight="700" fill="#991B1B">未使用 ＆ 全局危险帧拦截</text>
  <text x="520" y="76" font-size="9.1" fill="#B91C1C">✘ 未使用通道：禁止持续/定时上电输出</text>
  <text x="520" y="92" font-size="9.5" fill="#B91C1C">✘ 终端/原始 HEX 同样受安全策略拦截</text>
  <text x="520" y="108" font-size="9.5" fill="#B91C1C" font-weight="700">✘ 永久拒绝：单独写 MODBUS 0005 / 0007</text>
  <text x="520" y="124" font-size="9" fill="#7F1D1D">（写 0005 会取消全部定时转为一直通电）</text>
  <text x="520" y="142" font-size="9" fill="#7F1D1D">✘ 广播（地址 0）禁止上电，开锁需二次确认</text>
</svg>
"""


def phone_frame(uri: str, caption: str, fig_no: str) -> str:
    return f"""
<div class="phone-card">
  <div class="phone-bezel">
    <div class="phone-notch"><span></span></div>
    <img src="{uri}" alt="{html.escape(caption)}" />
  </div>
  <div class="fig-caption"><strong>{html.escape(fig_no)}</strong>　{html.escape(caption)}</div>
</div>
"""


def build_html() -> str:
    imgs = {name: img_data_uri(name) for name in EXPECTED_SCREENSHOTS}

    dip_rows = []
    for addr in range(1, 16):
        s1 = "ON" if (addr & 1) else "OFF"
        s2 = "ON" if (addr & 2) else "OFF"
        s3 = "ON" if (addr & 4) else "OFF"
        s4 = "ON" if (addr & 8) else "OFF"
        dip_rows.append(
            f"<tr><td class='tc'><strong>{addr}</strong></td>"
            f"<td class='tc mono'>{s4} {s3} {s2} {s1}</td>"
            f"<td class='tc'>{s1}</td><td class='tc'>{s2}</td><td class='tc'>{s3}</td><td class='tc'>{s4}</td></tr>"
        )
    dip_table_html = "\n".join(dip_rows)

    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<title>12 路锁控板测试配置 App 用户手册</title>
<style>
  @page {{
    size: A4 portrait;
    margin: 18mm 14mm 18mm 14mm;
    @top-left {{
      content: "12 路锁控板测试配置 App 用户手册（v1.0.0）";
      font-family: "Noto Sans CJK SC", "Noto Sans", sans-serif;
      font-size: 8.5pt;
      color: #64748B;
      border-bottom: 0.5pt solid #CBD5E1;
      padding-bottom: 3pt;
      margin-bottom: 4pt;
    }}
    @top-right {{
      content: "定昌电子 DC_锁控_12 · 485 双协议";
      font-family: "Noto Sans CJK SC", "Noto Sans", sans-serif;
      font-size: 8.5pt;
      color: #64748B;
      border-bottom: 0.5pt solid #CBD5E1;
      padding-bottom: 3pt;
      margin-bottom: 4pt;
    }}
    @bottom-left {{
      content: "现场操作与调试依据 · 严禁对电控锁持续通电";
      font-family: "Noto Sans CJK SC", "Noto Sans", sans-serif;
      font-size: 8.5pt;
      color: #64748B;
      border-top: 0.5pt solid #CBD5E1;
      padding-top: 3pt;
    }}
    @bottom-right {{
      content: "第 " counter(page) " 页 / 共 " counter(pages) " 页";
      font-family: "Noto Sans CJK SC", "Noto Sans", sans-serif;
      font-size: 8.5pt;
      color: #475569;
      border-top: 0.5pt solid #CBD5E1;
      padding-top: 3pt;
    }}
  }}

  @page :first {{
    margin: 0;
    @top-left {{ content: none; }}
    @top-right {{ content: none; }}
    @bottom-left {{ content: none; }}
    @bottom-right {{ content: none; }}
  }}

  * {{ box-sizing: border-box; }}
  body {{
    margin: 0;
    padding: 0;
    font-family: "Noto Sans CJK SC", "Noto Sans", "Droid Sans Fallback", sans-serif;
    font-size: 9.8pt;
    line-height: 1.56;
    color: #1E293B;
    -webkit-print-color-adjust: exact;
    print-color-adjust: exact;
  }}

  .mono, code {{
    font-family: "Noto Sans Mono CJK SC", "DejaVu Sans Mono", monospace;
    font-size: 8.8pt;
    background: #F1F5F9;
    padding: 1px 4px;
    border-radius: 3px;
    color: #0F172A;
  }}

  /* 封面 */
  .cover-page {{
    width: 100%;
    min-height: 296mm;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    background: #FFFFFF;
    page-break-after: always;
  }}
  .cover-hero {{
    background: linear-gradient(135deg, #0D47A1 0%, #1565C0 58%, #00796B 100%);
    color: #FFFFFF;
    padding: 36mm 18mm 22mm 18mm;
    position: relative;
  }}
  .cover-badge {{
    display: inline-block;
    background: rgba(255,255,255,0.18);
    border: 1px solid rgba(255,255,255,0.45);
    border-radius: 999px;
    padding: 3px 12px;
    font-size: 9.5pt;
    letter-spacing: 0.6px;
    margin-bottom: 14px;
  }}
  .cover-title {{
    font-size: 26pt;
    font-weight: 800;
    line-height: 1.25;
    margin: 0 0 10px 0;
  }}
  .cover-subtitle {{
    font-size: 12.5pt;
    color: #DBEAFE;
    margin: 0;
    font-weight: 400;
  }}
  .cover-body {{
    padding: 12mm 18mm 0 18mm;
    flex: 1;
  }}
  .meta-grid {{
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px 14px;
    margin-bottom: 16px;
  }}
  .meta-item {{
    background: #F8FAFC;
    border: 1px solid #E2E8F0;
    border-left: 4px solid #1565C0;
    border-radius: 6px;
    padding: 9px 12px;
  }}
  .meta-label {{ font-size: 8.5pt; color: #64748B; margin-bottom: 2px; }}
  .meta-val {{ font-size: 10.2pt; font-weight: 700; color: #0F172A; }}
  .cover-danger {{
    background: #FEF2F2;
    border: 1.5px solid #DC2626;
    border-left: 6px solid #DC2626;
    border-radius: 8px;
    padding: 12px 14px;
    margin: 14px 0;
  }}
  .cover-danger h3 {{
    margin: 0 0 6px 0;
    color: #991B1B;
    font-size: 11.5pt;
  }}
  .cover-danger p {{
    margin: 3px 0;
    color: #7F1D1D;
    font-size: 9.5pt;
  }}
  .cover-highlights {{
    background: #F0F9FF;
    border: 1px solid #BAE6FD;
    border-radius: 8px;
    padding: 12px 14px;
    margin-top: 12px;
  }}
  .cover-highlights h4 {{ margin: 0 0 6px 0; color: #0369A1; font-size: 10.5pt; }}
  .cover-highlights ul {{ margin: 0; padding-left: 18px; }}
  .cover-highlights li {{ margin: 3px 0; font-size: 9.4pt; color: #0C4A6E; }}
  .cover-footer {{
    padding: 8mm 18mm 12mm 18mm;
    border-top: 1px solid #E2E8F0;
    display: flex;
    justify-content: space-between;
    font-size: 8.8pt;
    color: #64748B;
  }}

  /* 目录页 */
  .toc-box {{
    background: #F8FAFC;
    border: 1px solid #CBD5E1;
    border-radius: 8px;
    padding: 14px 16px;
    margin-bottom: 14px;
  }}
  .toc-grid {{
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 6px 18px;
  }}
  .toc-item {{
    padding: 5px 0;
    border-bottom: 1px dashed #CBD5E1;
    font-size: 9.6pt;
    display: flex;
    align-items: baseline;
    gap: 8px;
  }}
  .toc-num {{
    display: inline-block;
    min-width: 24px;
    font-weight: 700;
    color: #1565C0;
  }}

  /* 章节标题 */
  h1.sec {{
    font-size: 15.5pt;
    font-weight: 800;
    color: #0D47A1;
    border-bottom: 2px solid #1565C0;
    padding-bottom: 4px;
    margin: 14px 0 10px 0;
    break-after: avoid;
    page-break-after: avoid;
  }}
  h2.sub {{
    font-size: 11.5pt;
    font-weight: 700;
    color: #0F172A;
    border-left: 4px solid #00796B;
    padding-left: 8px;
    margin: 12px 0 6px 0;
    break-after: avoid;
    page-break-after: avoid;
  }}
  p {{ margin: 5px 0; }}
  ul, ol {{ margin: 4px 0 8px 0; padding-left: 19px; }}
  li {{ margin: 3px 0; }}

  .page-break {{ page-break-before: always; break-before: page; }}

  /* 提示框 */
  .callout {{
    border-radius: 6px;
    padding: 9px 12px;
    margin: 8px 0;
    break-inside: avoid;
    page-break-inside: avoid;
    font-size: 9.3pt;
  }}
  .callout-danger {{ background: #FEF2F2; border: 1px solid #FECACA; border-left: 4px solid #DC2626; color: #7F1D1D; }}
  .callout-warn {{ background: #FFF7ED; border: 1px solid #FED7AA; border-left: 4px solid #E65100; color: #7C2D12; }}
  .callout-info {{ background: #EFF6FF; border: 1px solid #BFDBFE; border-left: 4px solid #1565C0; color: #1E3A8A; }}
  .callout-ok {{ background: #F0FDF4; border: 1px solid #BBF7D0; border-left: 4px solid #2E7D32; color: #14532D; }}
  .callout-title {{ font-weight: 700; margin-bottom: 3px; }}

  /* 表格 */
  table {{
    width: 100%;
    border-collapse: collapse;
    margin: 8px 0 10px 0;
    font-size: 9.1pt;
  }}
  th, td {{
    border: 1px solid #CBD5E1;
    padding: 5px 8px;
    text-align: left;
    vertical-align: top;
  }}
  th {{
    background: #E2E8F0;
    color: #0F172A;
    font-weight: 700;
  }}
  tr {{ break-inside: avoid; page-break-inside: avoid; }}
  tr:nth-child(even) td {{ background: #F8FAFC; }}
  .tc {{ text-align: center; }}

  /* 矢量图容器 */
  .svg-wrap {{
    margin: 8px 0 10px 0;
    break-inside: avoid;
    page-break-inside: avoid;
  }}

  /* 图文双栏布局 */
  .split {{
    display: flex;
    gap: 14px;
    align-items: flex-start;
    margin: 8px 0 12px 0;
    break-inside: avoid;
    page-break-inside: avoid;
  }}
  .split-left {{ flex: 0 0 43%; }}
  .split-right {{ flex: 1; min-width: 0; }}
  .split-2col {{
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 14px;
    margin: 8px 0 10px 0;
    break-inside: avoid;
    page-break-inside: avoid;
  }}

  /* 手机真机截图框 */
  .phone-card {{
    break-inside: avoid;
    page-break-inside: avoid;
  }}
  .phone-bezel {{
    background: #1E293B;
    border-radius: 14px;
    padding: 8px 6px 8px 6px;
    box-shadow: 0 3px 10px rgba(15, 23, 42, 0.14);
    border: 1px solid #334155;
  }}
  .phone-notch {{
    display: flex;
    justify-content: center;
    margin-bottom: 5px;
  }}
  .phone-notch span {{
    display: inline-block;
    width: 34px;
    height: 4px;
    border-radius: 2px;
    background: #475569;
  }}
  .phone-bezel img {{
    display: block;
    width: 100%;
    height: auto;
    max-height: 126mm;
    object-fit: contain;
    background: #FFFFFF;
    border-radius: 6px;
  }}
  .split-2col .phone-bezel img {{
    max-height: 112mm;
  }}
  .fig-caption {{
    margin-top: 5px;
    font-size: 8.6pt;
    color: #475569;
    text-align: center;
    line-height: 1.35;
  }}

  /* 编号步骤列表（仅直接子代 li 使用 flex，防止内嵌 ul/li 错排） */
  .steps {{
    list-style: none;
    padding-left: 0;
    margin: 4px 0;
  }}
  .steps > li {{
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin: 6px 0;
    break-inside: avoid;
    page-break-inside: avoid;
  }}
  .steps > li ul {{
    margin: 3px 0 2px 0;
    padding-left: 16px;
    list-style: disc;
  }}
  .steps > li ul li {{
    display: list-item;
    margin: 2px 0;
  }}
  .step-badge {{
    flex: 0 0 20px;
    height: 20px;
    line-height: 20px;
    border-radius: 50%;
    background: #1565C0;
    color: #FFFFFF;
    font-size: 8.5pt;
    font-weight: 700;
    text-align: center;
    margin-top: 1px;
  }}
  .step-badge.warn {{ background: #E65100; }}
  .step-badge.green {{ background: #2E7D32; }}

  .card-box {{
    background: #F8FAFC;
    border: 1px solid #CBD5E1;
    border-radius: 7px;
    padding: 9px 11px;
    margin: 7px 0;
    break-inside: avoid;
    page-break-inside: avoid;
  }}
  .card-box h4 {{
    margin: 0 0 4px 0;
    font-size: 10pt;
    color: #0F172A;
  }}
  .two-col-text {{
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
  }}
</style>
</head>
<body>

<!-- ==================== 封面 ==================== -->
<div class="cover-page">
  <div class="cover-hero">
    <div class="cover-badge">现场操作与验收官方图文指南 · ANDROID 终端应用</div>
    <h1 class="cover-title">12 路锁控板测试配置 App<br/>用户手册</h1>
    <p class="cover-subtitle">面向现场实施、测试与运维人员 · 支持私有协议与 MODBUS RTU 双协议</p>
  </div>

  <div class="cover-body">
    <div class="meta-grid">
      <div class="meta-item">
        <div class="meta-label">适用 App 版本</div>
        <div class="meta-val">v1.0.0（lock-board-485-v1.0.0.apk）</div>
      </div>
      <div class="meta-item">
        <div class="meta-label">适用硬件与固件</div>
        <div class="meta-val">12 路锁控板（LOCK_12_MODBUS_TIMED_20261006）</div>
      </div>
      <div class="meta-item">
        <div class="meta-label">Android 系统要求</div>
        <div class="meta-val">Android 8.0 及以上（目标设备 Android 11 / API 30）</div>
      </div>
      <div class="meta-item">
        <div class="meta-label">通信链路支持</div>
        <div class="meta-val">USB 转 485 (OTG) · 板载串口 · TCP 串口服务器 · 模拟器</div>
      </div>
    </div>

    <div class="card-box">
      <h4>正式版安装包签名指纹核验</h4>
      <p style="margin:2px 0;font-size:9pt;">安装前请核对签名证书 SHA-256 指纹，指纹不一致的安装包严禁投入现场使用：</p>
      <p style="margin:4px 0 0 0;"><code>06a7d6003fcf557f2733c861c654ca97b1eebf40a60883bb2bfe63a94fc7c77b</code></p>
    </div>

    <div class="cover-danger">
      <h3>⚠ 现场操作安全红线（必读）</h3>
      <p>1. <strong>真总线实操风险</strong>：连接真实 RS485 总线后，App 发出的指令会<strong>真实弹开柜门电控锁、真实给负载通电</strong>。操作前请务必确认柜门周边无人、机械结构无卡阻，且负载可以安全通电。</p>
      <p>2. <strong>严防烧坏电控锁</strong>：电控锁仅允许<strong>短脉冲开锁</strong>（约 0.35–1.4 s），<strong>绝对禁止持续通电</strong>！请先在“配置 → 板卡接线档案”中准确划分“电控锁”与“灯/继电器”，并在现场核对一致后再点“确认接线”。</p>
      <p>3. <strong>模拟器仅用于培训</strong>：内置模拟器全部通过只代表 App 逻辑正确，<strong>不能替代真机实物验收</strong>。</p>
    </div>

    <div class="cover-highlights">
      <h4>本手册阅读指南</h4>
      <ul>
        <li><strong>首次上机必读</strong>：第 1 章（安装）→ 第 2 章（接线与拨码）→ 第 4 章（接线档案）→ 第 5 章（连接与扫描）。</li>
        <li><strong>日常调试与验收</strong>：第 6 章（手动控制与安全门）→ 第 7 章（自动测试与寿命测试）→ 第 10 章（故障排查与真机验收）。</li>
        <li><strong>图文说明</strong>：本手册中的手机界面截图均由 v1.0.0 真实中文界面渲染采集，按钮文案与现场软件完全一致。</li>
      </ul>
    </div>
  </div>

  <div class="cover-footer">
    <span>版本：1.0.3 · Copyright © 2026 郭春良 · 项目与反馈：https://github.com/chun79/lock-board-485-app</span>
    <span>许可证：Apache License 2.0</span>
  </div>
</div>

<!-- ==================== 目录与第 1 章 ==================== -->
<h1 class="sec">目录与配图索引</h1>
<div class="toc-box">
  <div class="toc-grid">
    <div class="toc-item"><span class="toc-num">01</span><span>安装与升级（系统要求、签名核对）</span></div>
    <div class="toc-item"><span class="toc-num">06</span><span>手动控制（控制页、安全门与批量操作）</span></div>
    <div class="toc-item"><span class="toc-num">02</span><span>硬件准备（485 接线、拨码地址、串口参数）</span></div>
    <div class="toc-item"><span class="toc-num">07</span><span>自动测试与寿命测试（测试页与报告导出）</span></div>
    <div class="toc-item"><span class="toc-num">03</span><span>界面总览与标准作业闭环流程</span></div>
    <div class="toc-item"><span class="toc-num">08</span><span>调试终端（构造帧、安全策略与通讯日志）</span></div>
    <div class="toc-item"><span class="toc-num">04</span><span>配置接线档案（防烧锁核心设定）</span></div>
    <div class="toc-item"><span class="toc-num">09</span><span>配置页其他设置（时序、拨码换算、模拟器）</span></div>
    <div class="toc-item"><span class="toc-num">05</span><span>连接、总线扫描与遗留输出处理</span></div>
    <div class="toc-item"><span class="toc-num">10</span><span>常见问题排查与真机验收要点</span></div>
  </div>
  <p style="margin:8px 0 0 0;font-size:8.8pt;color:#475569;">
    <strong>技术原理示意图</strong>：图 1（RS485 总线拓扑） · 图 2（拨码地址换算） · 图 3（防烧锁安全闭环流程） · 图 4（通道类型安全红线）<br/>
    <strong>App 真实界面截图</strong>：图 5（连接扫描） · 图 6（接线档案） · 图 7–9（控制页上/中/下部） · 图 10–11（自动测试与报告） · 图 12（调试终端） · 图 13（拨码与配置）
  </p>
</div>

<h1 class="sec">第 1 章　安装与升级</h1>
<div class="two-col-text">
  <div class="card-box">
    <h4>1.1 设备与系统要求</h4>
    <ul>
      <li><strong>系统版本</strong>：Android 8.0（API 26）及以上，推荐 Android 11（API 30）。</li>
      <li><strong>OTG 供电</strong>：若使用 USB 转 485 适配器，Android 设备需支持 USB Host 模式。</li>
      <li><strong>板载串口权限</strong>：若使用主板内置 <code>/dev/ttyS*</code>，主板系统固件须提前开放对应设备节点的读写权限与 SELinux 策略。</li>
    </ul>
  </div>
  <div class="card-box">
    <h4>1.2 安装、覆盖升级与签名校验</h4>
    <ul>
      <li>将安装包 <code>lock-board-485-v1.0.0.apk</code> 拷贝到 Android 设备，点击安装；系统弹出“未知来源应用”提示时允许安装。</li>
      <li><strong>覆盖升级</strong>：新版本直接覆盖安装即可，已配置的板卡接线档案与参数会自动保留。</li>
      <li><strong>签名核对</strong>：正式发布版 SHA-256 证书指纹须为 <code>06a7d600…c7c77b</code>（完整指纹见封面）。若发现指纹不符，请勿在现场设备上安装使用。</li>
    </ul>
  </div>
</div>

<h1 class="sec">第 2 章　硬件准备与拨码地址</h1>
<p>开始上电测试前，请逐项检查 <strong>RS485 通信线极性</strong>、<strong>板卡拨码地址</strong> 与 <strong>串口参数</strong>。同一固件同时支持私有协议和 MODBUS RTU，硬件无需切换跳线。</p>

<div class="svg-wrap">{svg_bus_topology()}</div>

<div class="svg-wrap">{svg_dip_diagram()}</div>

<div class="two-col-text">
  <div class="card-box">
    <h4>2.1 接线与串口通信参数</h4>
    <ul>
      <li><strong>485 接线</strong>：锁控板 <code>A</code> 端接适配器/串口服务器 <code>A</code>，<code>B</code> 端接 <code>B</code>，多块板采用手拉手方式挂接在同一条 485 总线上。</li>
      <li><strong>串口默认参数</strong>：<code>9600 8N1</code>（波特率 9600、8 位数据位、无校验、1 位停止位）。App 默认即为此值，通常无需改动。</li>
      <li><strong>地址范围</strong>：每块板通过 4 位拨码设置地址 <code>1–15</code>（<code>地址 = S1×1 + S2×2 + S3×4 + S4×8</code>，拨至 <code>ON</code> 为 1）。全 <code>OFF</code>（地址 0）为无效地址，禁止在总线上与常规板混用；同一总线上的板卡地址严禁重复。</li>
    </ul>
  </div>
  <div>
    <table>
      <thead>
        <tr><th class="tc">地址</th><th class="tc">拨码(4321)</th><th class="tc">S1(1)</th><th class="tc">S2(2)</th><th class="tc">S3(4)</th><th class="tc">S4(8)</th></tr>
      </thead>
      <tbody>
        {dip_table_html}
      </tbody>
    </table>
  </div>
</div>

<!-- ==================== 第 3 章 & 第 4 章 ==================== -->
<div class="page-break"></div>
<h1 class="sec">第 3 章　界面总览与标准作业流程</h1>
<p>App 底部导航栏包含五个主功能页面，推荐按 <strong>配置档案 → 连接扫描 → 手动控制 / 自动测试 → 终端排查</strong> 的顺序使用。首次接新机柜时，务必先完成第 4 章“板卡接线档案”配置，再连接真实总线。</p>

<table>
  <thead>
    <tr><th style="width:14%">底部页签</th><th style="width:36%">核心功能</th><th style="width:50%">现场典型用途</th></tr>
  </thead>
  <tbody>
    <tr><td><strong>连接</strong></td><td>选择 USB / 串口 / TCP / 模拟器链路，连接并扫描地址 1–15</td><td>建立通信连接、核对双协议版本（私有 ≥ 02 00 / MODBUS ≥ 0002 且一致）、处理异常断线遗留输出</td></tr>
    <tr><td><strong>控制</strong></td><td>单路开锁、读反馈、灯/继电器持续与定时通电、批量操作</td><td>点对点核验每扇门锁弹开与反馈极性、开关柜灯、人工清账断电</td></tr>
    <tr><td><strong>测试</strong></td><td>一键自动执行 T0–T11 / M3–M7 / X1–X2 与寿命循环测试</td><td>整柜或整板出厂/进场验收，导出 TXT / JSON 验收报告</td></tr>
    <tr><td><strong>终端</strong></td><td>构造私有帧 / MODBUS 帧 / 原始 HEX，实时彩色通讯日志</td><td>协议级抓包排查、异常帧响应验证、导出完整通讯流水日志</td></tr>
    <tr><td><strong>配置</strong></td><td>板卡接线档案、上电安全上限、通信时序、拨码换算、模拟器</td><td>划定各路是锁还是灯、有无反馈线、微调超时期限、离线培训</td></tr>
  </tbody>
</table>

<div class="svg-wrap">{svg_safety_workflow()}</div>

<h1 class="sec">第 4 章　配置接线档案（重要）</h1>
<p>在 <strong>配置 → 板卡接线档案</strong> 中，需要按板卡地址（1–15）为该板的第 1–12 路逐一设定通道属性。软件内部的安全策略完全依赖此档案防止误烧电控锁。</p>

<div class="svg-wrap">{svg_channel_safety_matrix()}</div>

<div class="split">
  <div class="split-left">
    {phone_frame(imgs["02_settings_profile.png"], "配置页：板卡接线档案（设置名称、通道类型、有反馈及标签）", "图 6")}
  </div>
  <div class="split-right">
    <h2 class="sub">档案配置操作要点</h2>
    <ul class="steps">
      <li><span class="step-badge">1</span><div><strong>选择板卡地址并命名</strong>：在顶部下拉选择板卡地址（旁边红框会同步显示对应拨码），输入易于识别的名称（例如 <code>A 柜上层</code>）。</div></li>
      <li><span class="step-badge">2</span><div><strong>逐路指定通道类型</strong>：
        <ul>
          <li><code>电控锁</code>：默认类型，只允许短脉冲开锁，<strong>严禁持续通电</strong>。</li>
          <li><code>灯/继电器</code>：允许持续通电与定时通电。<strong>必须确认现场确实未接电控锁</strong>才能设为该类型。</li>
          <li><code>未使用</code>：未接负载的空通道，一律拒绝上电。</li>
        </ul>
      </div></li>
      <li><span class="step-badge">3</span><div><strong>勾选“有反馈”</strong>：仅当该路电控锁实际接了门磁/锁状态反馈线时保持勾选；<strong>没有接反馈线的通道请务必取消勾选</strong>，否则自动测试会因读不到“关→开”变化而判为失败。</div></li>
      <li><span class="step-badge">4</span><div><strong>填写通道标签</strong>：如 <code>1号门</code>、<code>照明灯</code>，标签会同步显示在控制页卡片和最终测试报告中。</div></li>
      <li><span class="step-badge warn">!</span><div><strong>档案变更与电控锁定时</strong>：在连接期间修改任意板卡档案，系统会自动撤销该板的“确认接线”授权以策安全。电控锁默认禁止定时通电；确有特殊需要时，可在 <strong>配置 → 上电输出安全</strong> 中设定最长定时秒数（0 表示禁止）。</div></li>
    </ul>
  </div>
</div>

<div class="two-col-text" style="margin-top:8px;">
  <div class="card-box">
    <h4>4.1 现场接线核对清单（配置前必查）</h4>
    <ul>
      <li><strong>多板逐一录入</strong>：每块板卡地址（1–15）拥有独立档案，切换顶部地址下拉框即可分别配置。</li>
      <li><strong>快速重置按钮</strong>：底部提供“全部设为电控锁”与“全部无反馈”快捷按钮，方便批量初始化。</li>
      <li><strong>未知硬件先保守</strong>：若现场某路负载类型尚未核实，请保持默认的 <code>电控锁</code> 或设为 <code>未使用</code>。</li>
    </ul>
  </div>
  <div class="callout callout-danger" style="margin:7px 0;">
    <div class="callout-title">严禁把电控锁错配为“灯/继电器”</div>
    一旦错配并在控制页点击“确认接线”，App 将放行 <code>0x16/0x1A</code> 及 MODBUS <code>0004/0008</code> 持续输出指令，可能导致电控锁线圈长时间通电发热甚至烧毁！每次连接后都必须在现场目视确认后再点“确认接线”。
  </div>
</div>

<!-- ==================== 第 5 章 ==================== -->
<div class="page-break"></div>
<h1 class="sec">第 5 章　连接、总线扫描与遗留输出处理</h1>
<div class="split">
  <div class="split-left">
    {phone_frame(imgs["01_connect_scan.png"], "连接页：已连接链路状态与总线扫描（地址 1–15 双协议探测）", "图 5")}
  </div>
  <div class="split-right">
    <h2 class="sub">5.1 选择链路与扫描总线</h2>
    <ul class="steps">
      <li><span class="step-badge">1</span><div><strong>选择通信链路</strong>（仅在未连接时可改）：
        <ul>
          <li><strong>USB 转 485</strong>：插入 OTG 适配器后点击“刷新”选中设备；首次连接弹出 Android USB 权限框时请点“允许”。</li>
          <li><strong>板载串口</strong>：选择 <code>/dev/ttyS*</code> 节点；仅当主板收发器需要靠 RTS 控制方向（非自动换向芯片）时，开启“启用内核 RS485 模式”。</li>
          <li><strong>TCP 串口服务器</strong>：填写服务器 IP 与端口（默认 8899）。串口服务器须设为 <strong>TCP Server 透传模式</strong>，且自身串口参数设置为 <code>9600 8N1</code>。</li>
          <li><strong>模拟器</strong>：无需硬件即可操作，<strong>模拟器结果不代表实物验收</strong>。</li>
        </ul>
      </li>
      <li><span class="step-badge">2</span><div><strong>连接</strong>：点击 <strong>连接</strong>，顶部与状态栏变为绿色“已连接”。</div></li>
      <li><span class="step-badge">3</span><div><strong>扫描总线</strong>：点击 <strong>扫描总线</strong>，App 会依次用私有 <code>0x01</code> 与 MODBUS <code>03(0001)</code> 探测地址 1–15，列出在线板卡及其双协议版本号。</div></li>
      <li><span class="step-badge green">4</span><div><strong>核对版本并选定板卡</strong>：2026-10-06 文档基准版本为 <strong>私有 ≥ <code>02 00</code>（预留 00）、MODBUS ≥ <code>0002</code> 且双方一致</strong>（高版本固件如 <code>03 00 / 0003</code> 兼容）。若某块板提示“只有一种协议回复，与文档不符”，请核查板卡固件版本或通信干扰。点击列表项即可选中该板作为当前控制对象。</div></li>
    </ul>
  </div>
</div>

<h2 class="sub">5.2 安全断开与“遗留输出”处置规则</h2>
<div class="two-col-text">
  <div class="callout callout-info">
    <div class="callout-title">正常断开（尝试关断并清账）</div>
    点击 <strong>断开</strong> 时，如果本 App 开启的灯/继电器持续输出尚未关闭，会弹窗提醒并尝试逐路发送 <code>0x16</code> 关断及读回 <code>0004</code> 核对，随后关闭端口。<strong>注意：即使读回失败或通道仍可能带电，App 仍会完成断开并明确提示“可能仍带电”</strong>（未确认通道会留存为遗留输出），切勿把“已断开”当成断电依据，须立即到现场核实物理断电。
  </div>
  <div class="callout callout-warn">
    <div class="callout-title">异常中断（遗留输出恢复机制）</div>
    若带电期间发生 USB 线松脱、适配器拔出等链路意外中断，App 来不及执行关断，会自动把“可能仍带电”的通道登记留存：<br/>
    • <strong>原适配器重连</strong>（有序列号）：自动带入登记并读回 <code>0004</code> 核对。<br/>
    • <strong>无序列号 USB 适配器</strong>：App 无法识别是否为同一总线，连接页顶部会出现红色提示卡片，请您按实际情况选择：确认是原总线时点 <strong>“是同一条总线：带入并读回”</strong>；已在现场确认断电时点 <strong>“现场已确认断电”</strong> 清除登记。
  </div>
</div>

<!-- ==================== 第 6 章 ==================== -->
<div class="page-break"></div>
<h1 class="sec">第 6 章　手动控制与上电安全门</h1>
<p>切换到底部 <strong>控制</strong> 页，可以对当前选中板卡逐路或批量下发控制指令，实时监测门锁反馈和持续输出控制字。</p>

<div class="split-2col">
  {phone_frame(imgs["04_control_top.png"], "控制页上部：当前板切换、协议选择、0004 读回与接线确认门", "图 7")}
  {phone_frame(imgs["05_control_channels.png"], "控制页中部：12 路通道卡片（绿点=打开，橙字=0004 持续输出开）", "图 8")}
</div>

<div class="two-col-text" style="margin-top:6px;">
  <div class="card-box">
    <h4>6.1 通道状态颜色与标签说明</h4>
    <ul>
      <li><strong style="color:#2E7D32;">● 绿点 / 打开</strong>：门磁反馈检测到门锁已弹开（1 = 打开）。</li>
      <li><strong style="color:#546E7A;">● 灰点 / 关闭</strong>：门锁处于闭合关闭状态（0 = 关闭）。</li>
      <li><strong style="color:#64748B;">● 浅灰 / 未知</strong>：本次连接尚未读取该路反馈（可点“读反馈”刷新）。</li>
      <li><strong style="color:#E65100;">● 橙字：0004 读回：持续输出开</strong>：MODBUS 0004 控制字显示该路处于持续或定时通电中。</li>
    </ul>
  </div>
  <div class="callout callout-warn" style="margin:7px 0;">
    <div class="callout-title">物理输出叠加规则：实际输出 = 持续 OR 脉冲</div>
    寄存器 <code>0004</code> 仅代表“持续输出控制字”，不代表端子最终物理电平。同一路电控锁若同时在执行开锁脉冲，即使 <code>0004</code> 读回为 0，物理端子在脉冲窗口结束前仍可能处于通电状态；脉冲与持续输出均结束后才会完全断电。
  </div>
</div>

<!-- ==================== 控制页下部与操作说明（第 6 章续） ==================== -->
<div class="page-break"></div>
<h2 class="sub" style="margin-top:0;">6.2 控制页操作步骤与批量控制说明</h2>
<div class="split">
  <div class="split-left">
    {phone_frame(imgs["06_control_batch.png"], "控制页下部：通道多选器与批量开锁 / 整体输出操作区", "图 9")}
  </div>
  <div class="split-right">
    <h2 class="sub">控制页关键操作说明</h2>
    <ul class="steps">
      <li><span class="step-badge">1</span><div><strong>当前板与控制协议</strong>：顶部可切换地址（1–15）与控制协议（<strong>私有协议</strong> / <strong>MODBUS RTU</strong>），支持一键“读版本”、“读反馈”及开启“自动轮询反馈”。</div></li>
      <li><span class="step-badge warn">2</span><div><strong>接线档案确认（上电安全门）</strong>：持续通电和定时通电前，必须对照屏幕上列出的灯/继电器通道号与现场接线核对一致，再点击 <strong>确认接线</strong>。每次重新连接都要重新确认；修改接线档案后确认自动撤销。</div></li>
      <li><span class="step-badge">3</span><div><strong>12 路通道卡片</strong>：
        <ul>
          <li><code>开锁</code>：下发短脉冲开锁（约 0.35–1.4 s）；<code>读</code>：读取单路门状态反馈。</li>
          <li>灯/继电器通道额外显示 <code>开</code>、<code>关</code> 与 <code>定时</code>（按上方输入的“定时秒数”通电，到期由板卡自动撤销）。</li>
          <li>电控锁通道固定显示灰色的 <code>定时通电 N s</code> 按钮：默认配置（上限 0 s）下点击会被安全策略直接拒绝；仅当配置中设置了允许的最长秒数且在上限内时才会执行。</li>
        </ul>
      </li>
      <li><span class="step-badge">4</span><div><strong>读回 0004 与逐路关断</strong>：<code>0004</code> 是板卡的“持续输出控制字”（非物理电平；脉冲开锁期间硬件输出也会通电）。点 <strong>逐路关断并读回确认</strong> 可一键关掉本 App 开启的全部输出并读回清账；若读不回状态，在现场目视断电后可点 <strong>现场已确认断电</strong>（仅清 App 记录，不发指令）。</div></li>
      <li><span class="step-badge warn">5</span><div><strong>批量操作注意</strong>：先勾选通道（支持“选全部锁”“选全部输出”“清空”），再执行逐路开、同时开或整体输出。<strong>注意</strong>：<code>整体输出 0x1A / 0004</code> 会<strong>覆盖全部 12 路</strong>，未选中的灯/继电器会被关闭，且所有通道上的定时都会被取消！“同时开”会多路同时通电约 300 ms，请先确认电源功率余量。</div></li>
    </ul>
    <div class="callout callout-info" style="margin-top:6px;">
      当 <strong>自动测试</strong> 或 <strong>寿命测试</strong> 正在运行时，控制页上的手动操作会被自动锁定禁用，以防止总线指令冲突。
    </div>
  </div>
</div>

<!-- ==================== 第 7 章 ==================== -->
<div class="page-break"></div>
<h1 class="sec">第 7 章　自动测试与寿命测试</h1>
<p>切换到 <strong>测试</strong> 页，可对当前板或扫描发现的所有在线板执行标准化全项测试，并生成可分享存档的验收报告。</p>

<div class="split-2col">
  {phone_frame(imgs["07_test_setup.png"], "测试页上部：测试对象选择与 T1–T11 / M3–M7 / X1–X2 测试项清单", "图 10")}
  {phone_frame(imgs["08_test_report.png"], "测试页下部：自动测试报告结论与 TXT / JSON 报告导出按钮", "图 11")}
</div>

<div class="two-col-text">
  <div class="card-box">
    <h4>7.1 测试项分组说明</h4>
    <ul>
      <li><strong>通信与一致性</strong>：T1 双协议通信（0x01 / MODBUS 03 读 0001）、T2 四种读法反馈一致性、T9 私有异常帧容错、M7 MODBUS 异常响应与原子性、T10 双协议交替压力。</li>
      <li><strong>脉冲开锁</strong>：T3/M3 逐路开锁、T4/M4 逐路开多个、T5/M5 同时开、T6 私有 <code>0x14</code> 开全部 12 路。</li>
      <li><strong>输出控制（仅灯/继电器）</strong>：T7 持续输出开/关、T8 整体输出、T11 定时输出、M6 MODBUS 持续与定时、X1/X2 跨协议定时及 <code>0x1A</code> 取消定时。</li>
    </ul>
  </div>
  <div class="card-box">
    <h4>7.2 判定参数、执行预览与报告结论</h4>
    <ul>
      <li><strong>人工确认物理动作</strong>：在真实总线上<strong>强烈建议开启</strong>此开关。每次开锁或通电后，App 会弹窗请您确认锁是否弹开、灯是否亮灭；未开启时，物理动作在报告中只能记为 <strong>未验证</strong>。</li>
      <li><strong>要求锁反馈确认开锁</strong>：未接反馈线的路请在配置页取消“有反馈”，否则会被判失败。</li>
      <li><strong>动作预览与安全中止</strong>：点击 <strong>开始自动测试</strong> 后先显示“即将执行的动作”确认框；测试中可点 <strong>停止</strong> 发出协作中止请求（<strong>不可作为硬件急停使用</strong>），测试在下一次检查到停止请求时进入收尾（已发出的硬件动作可能仍在执行），随后逐路关断已开输出并读回 <code>0004</code>。</li>
      <li><strong>报告导出</strong>：结论分为 <strong>通过 / 警告 / 失败 / 未验证</strong>。可点击 <strong>分享 TXT</strong> 或 <strong>分享 JSON</strong> 导出。若报告提示“可能仍带电”，必须到现场核查。</li>
    </ul>
  </div>
</div>

<div class="callout callout-info">
  <div class="callout-title">7.3 寿命测试（Test 页最下方）</div>
  寿命测试按 <code>读反馈 0x11 → 开锁 0x10 → 读反馈 0x11</code> 循环运行。仅当检测到反馈由 <strong>“关 → 开”</strong> 变化时，才计为一次“已验证动作”（门未关回记为未验证）。可设置总轮数与每轮间隔；若<strong>连续 5 次失败</strong>，测试会自动中止以保护硬件。结果实时写入文件并支持分享。
</div>

<!-- ==================== 第 8 章 & 第 9 章 ==================== -->
<div class="page-break"></div>
<h1 class="sec">第 8 章　调试终端</h1>
<div class="split">
  <div class="split-left">
    {phone_frame(imgs["09_terminal.png"], "终端页：私有帧/MODBUS 帧自动校验构造与彩色实时通讯日志", "图 12")}
  </div>
  <div class="split-right">
    <h2 class="sub">8.1 帧发送与安全策略拦截</h2>
    <ul class="steps">
      <li><span class="step-badge">1</span><div><strong>私有帧构造</strong>：输入地址（0–255）、选择指令并填数据段 HEX，App 自动封装 <code>DC 02</code> 帧头与累加校验和并实时预览完整字节。</div></li>
      <li><span class="step-badge">2</span><div><strong>MODBUS 帧构造</strong>：输入地址（0–247）、功能码及数据 HEX，App 自动附加 <code>CRC16 (A001)</code> 并实时预览。地址设为 <code>0</code>（广播）时将弹出二次确认框。</div></li>
      <li><span class="step-badge">3</span><div><strong>原始 HEX 发送</strong>：在真实总线上，仅允许发送 1 个完整且合法的 <code>DC 02</code> 私有帧或 CRC 正确的 MODBUS 请求帧；任意乱码字节仅允许发送给模拟器。</div></li>
      <li><span class="step-badge warn">!</span><div><strong>统一安全策略检查</strong>：终端发出的每一条帧都同样受安全策略约束——向电控锁通道持续通电、广播上电、单独写 <code>0005</code> 或 <code>0007</code>（会取消全部定时导致一直通电）等危险指令都会被<strong>直接拒绝</strong>。</div></li>
      <li><span class="step-badge green">5</span><div><strong>通讯日志管理</strong>：顶部提供“杂散/回显”过滤开关、“跟随”自动滚屏、“清空”以及“导出”（导出 TXT 日志及误码统计）。蓝色为 TX 发送、绿色为正常 RX、橙色为警告/迟到回复、红色为错误/拦截。</div></li>
    </ul>
  </div>
</div>

<h2 class="sub">8.2 常用调试帧构造示例与拦截规则速查（以地址 01 为例）</h2>
<table>
  <thead>
    <tr><th style="width:20%">场景</th><th style="width:28%">构造方式 / 示例帧</th><th style="width:52%">说明与安全检查结果</th></tr>
  </thead>
  <tbody>
    <tr><td><strong>私有读版本</strong></td><td><code>DC 02 01 01 00 02</code></td><td>指令 <code>0x01</code>，数据段留空；正常回复 <code>DC FE 01 01 02 VV 00 ...</code>（基准 <code>02 00</code>，高版本如 <code>03 00</code> 兼容）。</td></tr>
    <tr><td><strong>私有开第 1 路</strong></td><td><code>DC 02 01 10 01 01 13</code></td><td>指令 <code>0x10</code>，数据段 <code>01</code>；在 0.35–1.4 s 动作结束后回复结果。</td></tr>
    <tr><td><strong>MODBUS 读版本</strong></td><td><code>01 03 00 01 00 01 D5 CA</code></td><td>功能码 <code>03</code>，数据 <code>00 01 00 01</code>；正常返回版本寄存器 ≥ <code>0002</code>（如 <code>0002</code> / <code>0003</code>）。</td></tr>
    <tr><td><strong>MODBUS 读输出字</strong></td><td><code>01 03 00 04 00 02 85 CA</code></td><td>读取寄存器 <code>0004–0005</code>，用于确认持续/定时输出状态（低 12 位有效）。</td></tr>
    <tr><td><strong style="color:#C62828;">禁止写 0005</strong></td><td><code>01 06 00 05 00 00 ...</code></td><td><strong style="color:#C62828;">直接拒绝</strong>：单独写 <code>0005</code> 会取消所有单路定时并将已通电通道转为一直通电。</td></tr>
  </tbody>
</table>

<div class="page-break"></div>
<h1 class="sec">第 9 章　配置页其他设置</h1>
<div class="split">
  <div class="split-left">
    {phone_frame(imgs["03_settings_dip.png"], "配置页下部：接线档案、交互式拨码地址换算器与内置模拟器参数", "图 13")}
  </div>
  <div class="split-right">
    <h2 class="sub">9.1 通信时序、拨码与模拟器</h2>
    <ul>
      <li><strong>通信时序（连接中修改立即生效）</strong>：包括查询超时、读重试次数、单路动作最长（ms）、同时脉冲、动作余量、帧间隔、迟到隔离与最长寿命、字节静默（默认 <code>50 ms</code>，范围 <code>5–1000 ms</code>，用于判定串口缓冲中残缺半帧何时作废并重同步；底层 MODBUS 协议帧以约 <code>5 ms</code> 静默分界）、扫描超时及轮询间隔。默认值均按厂家 2026-10-06 协议文档设定，一般无需修改。</li>
      <li><strong>丢弃发送回显</strong>：若使用的 RS485 适配器存在硬件回显（收端会读回自己刚发出的字节），请打开此开关，避免 MODBUS <code>05/06</code> 原帧回复与适配器回显混淆。</li>
      <li><strong>上电输出安全</strong>：设置“电控锁允许的最长定时通电 s”（默认 <code>0 = 禁止</code>）。</li>
      <li><strong>拨码地址换算</strong>：直接点击图形开关即可在 1–15 间实时换算拨码位置与地址。</li>
      <li><strong>内置模拟器</strong>：可配置多个虚拟板地址、时序、丢包/坏校验/回显，以及逐路注入故障（驱动损坏、无反馈、反馈卡开、错接如 <code>4&gt;5</code>），用于离线演示和人员培训。</li>
      <li><strong>恢复全部默认设置</strong>：一键清除所有档案、通信参数和模拟器设置并还原初始默认值。</li>
    </ul>
  </div>
</div>

<div class="two-col-text" style="margin-top:6px;">
  <div class="card-box">
    <h4>9.2 默认通信时序参考值（物理总线）</h4>
    <table>
      <thead><tr><th>参数</th><th class="tc">默认值</th><th>作用说明</th></tr></thead>
      <tbody>
        <tr><td>查询超时 / 读重试</td><td class="tc">300 ms / 2 次</td><td>只读查询超时及自动重试上限</td></tr>
        <tr><td>单路动作 / 余量</td><td class="tc">1400 / 1500 ms</td><td>防新脉冲打断正在执行的开锁</td></tr>
        <tr><td>同时脉冲 / 帧间隔</td><td class="tc">350 / 20 ms</td><td>0x18/0006 窗口与总线静默间隔</td></tr>
        <tr><td>迟到隔离 / 寿命</td><td class="tc">300 / 10000 ms</td><td>超时后排空迟到回复防止串位</td></tr>
      </tbody>
    </table>
  </div>
  <div class="card-box">
    <h4>9.3 模拟器故障注入输入语法</h4>
    <ul>
      <li><strong>虚拟板地址</strong>：逗号分隔 <code>1–15</code>，如 <code>1,2,4</code>。</li>
      <li><strong>驱动损坏 / 无反馈 / 反馈卡开</strong>：填通道号（逗号或空格分隔），如 <code>2, 7</code>，用于演练报错与报告判定。</li>
      <li><strong>错接映射</strong>：填写 <code>控制路&gt;实际反馈路</code>（如 <code>4&gt;5</code>），用于验证 T3/M3 串扰检出能力。</li>
      <li><strong>自动关门</strong>：<code>0</code> 表示需手动点“模拟关门”，非 0 为毫秒后自动关回。</li>
    </ul>
  </div>
</div>

<!-- ==================== 第 10 章 ==================== -->
<div class="page-break"></div>
<h1 class="sec">第 10 章　常见问题排查与真机验收要点</h1>

<h2 class="sub">10.1 现场常见问题排查表</h2>
<table>
  <thead>
    <tr><th style="width:28%">异常现象</th><th style="width:72%">排查原因与处理方法</th></tr>
  </thead>
  <tbody>
    <tr><td><strong>扫描不到任何板卡</strong></td><td>① 检查 RS485 A/B 线是否接反或接触不良；② 确认板卡拨码地址在 1–15 之间（不能全 OFF）；③ 核对串口参数为 <code>9600 8N1</code>；④ 若使用 TCP 网关，确认其工作在 <strong>TCP Server 透传模式</strong>。</td></tr>
    <tr><td><strong>两块板回复混乱、校验错</strong></td><td>同一总线上存在拨码地址重复的板卡，请逐块检查拨码开关并重新上电或重扫。</td></tr>
    <tr><td><strong>提示“只有一种协议回复”</strong></td><td>板卡固件与 2026-10-06 双协议规范不符（正常应同时回复私有 ≥ <code>02 00</code> 与 MODBUS ≥ <code>0002</code> 且双方一致），请核实固件版本或排查总线干扰。</td></tr>
    <tr><td><strong>提示“回复归属不明”</strong></td><td>更早发出的同类请求回复迟到了（03 读回复内容不带起始地址），App 为防止错位不将其当成本次结果，稍后重新读取即可。</td></tr>
    <tr><td><strong>开锁提示“结果未知”</strong></td><td>动作指令等待超时。为防止重复开锁引发二次弹门，App <strong>永不自动重发动作指令</strong>。请手动“读”反馈或到现场确认门锁实际状态。</td></tr>
    <tr><td><strong>持续通电指令被拒绝</strong></td><td>① 该通道档案为“电控锁”或“未使用”；② 本次连接尚未在控制页点“确认接线”（或改过档案导致确认撤销）；③ 试图单独写 <code>0005</code>/<code>0007</code> 或广播上电。</td></tr>
    <tr><td><strong>USB 断开后提示遗留输出</strong></td><td>适配器无序列号且带电期间断开，见第 5.2 节，按现场真实状况选“带入并读回”或“现场已确认断电”。</td></tr>
  </tbody>
</table>

<h2 class="sub">10.2 正式投入使用前的真机验收核心项（摘自《真机验收清单》）</h2>
<p>任何项目在正式交付前，请务必在真实 Android 设备与真实锁控板上完成以下关键核验（完整表格见仓库 <code>docs/真机验收清单.md</code>）：</p>
<table>
  <thead>
    <tr><th style="width:12%">类别</th><th style="width:28%">验收项</th><th style="width:60%">现场合格标准</th></tr>
  </thead>
  <tbody>
    <tr><td><strong>链路</strong></td><td>A1–A6（USB / 串口 / TCP / 回显）</td><td>USB 授权及拔出断链检测正常；板载串口读写正常；TCP 透传下 MODBUS 帧不被网关拆包；回显适配器打开过滤后 05/06 正常。</td></tr>
    <tr><td><strong>协议</strong></td><td>B1–B3（版本、极性与位映射）</td><td>版本确认私有 ≥ <code>02 00</code>（预留 00）、MODBUS ≥ <code>0002</code> 且双方一致；人工开关门验证 <code>1 = 打开</code>；单独开第 1、8、9、12 路，确认物理端子与通道号一一对应。</td></tr>
    <tr><td><strong>时序</strong></td><td>B4–B8（耗时、节流、定时精度）</td><td>实测 T3 单路 0x10 耗时落在 0.35–1.4 s；终端连发两次 05 间隔 ≥ 动作窗口；实测定时断电偏差在容差内且 X1/X2 跨协议覆盖正确。</td></tr>
    <tr><td><strong>功能</strong></td><td>C1–C5（开锁、输出与收尾清账）</td><td>开启“人工确认物理动作”跑通 T3/M3/T4/M4/T5/M5/T6；接灯跑通 T7/T8/T11/M6；电控锁通道尝试持续输出全部被拒；停止或断开后读回 <code>0004 = 0</code>。</td></tr>
    <tr><td><strong>综合</strong></td><td>C6–C11（多板、压力与稳定性）</td><td>2 块以上板级联无串扰；T10 跑 1000 次压力达标；寿命测试计数及连续 5 次失败自动停机正常；旋转屏幕不断连、杀进程重开配置保留。</td></tr>
  </tbody>
</table>

<div class="callout callout-ok">
  <div class="callout-title">附录：关于、技术支持与意见反馈</div>
  <strong>开发与版权</strong>：郭春良 &lt;<code>guo.chunliang@gmail.com</code>&gt; · Copyright © 2026 郭春良 · Apache License 2.0<br/>
  <strong>项目主页</strong>：<code>https://github.com/chun79/lock-board-485-app</code><br/>
  <strong>意见反馈</strong>：欢迎通过 GitHub Issues（<code>https://github.com/chun79/lock-board-485-app/issues</code>）或邮件提交问题与建议。<br/>
  <strong>配套文档</strong>：更多设计细节与协议边界说明可参阅仓库文档 <code>docs/设计说明.md</code>、<code>docs/协议文档说明-20261006.md</code>、<code>docs/真机验收清单.md</code> 及 <code>docs/示例报告-模拟器.txt</code>。
</div>

</body>
</html>
"""


def main() -> int:
    for name in EXPECTED_SCREENSHOTS:
        p = SCREENSHOTS_DIR / name
        if not p.is_file():
            print(f"错误：找不到截图 {p}，请先运行 ManualScreenshotCaptureTest。", file=sys.stderr)
            return 1

    if not CHROME_BIN.is_file():
        print(f"错误：找不到 Chrome 可执行文件 {CHROME_BIN}", file=sys.stderr)
        return 1

    html_content = build_html()
    HTML_PATH.write_text(html_content, encoding="utf-8")
    print(f"已生成 HTML: {HTML_PATH} ({len(html_content)} 字符)")

    PDF_PATH.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".chrome-profile-", dir=str(MANUAL_DIR)) as profile_dir:
        tmp_fd, tmp_name = tempfile.mkstemp(prefix=".manual-", suffix=".pdf.tmp", dir=str(PDF_PATH.parent))
        tmp_pdf = Path(tmp_name)
        try:
            # Close mkstemp fd so Chrome can overwrite it cleanly; delete on any failure.
            os.close(tmp_fd)
            cmd = [
                str(CHROME_BIN),
                "--headless=new",
                "--disable-gpu",
                f"--user-data-dir={profile_dir}",
                "--no-pdf-header-footer",
                f"--print-to-pdf={tmp_pdf}",
                HTML_PATH.resolve().as_uri(),
            ]
            res = subprocess.run(cmd, capture_output=True, text=True)
            if res.returncode != 0 or not tmp_pdf.is_file() or tmp_pdf.stat().st_size == 0:
                print(f"Chrome 渲染 PDF 失败 (exit={res.returncode}):\n{res.stderr}", file=sys.stderr)
                return 1
            tmp_pdf.replace(PDF_PATH)
        finally:
            tmp_pdf.unlink(missing_ok=True)

    print(f"已生成 PDF: {PDF_PATH} ({PDF_PATH.stat().st_size} 字节)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
