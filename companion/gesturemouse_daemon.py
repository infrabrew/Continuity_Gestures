#!/usr/bin/env python3
"""
GestureMouse Optional 5 GHz Wi-Fi Companion Daemon (Reference Implementation)

Standard Bluetooth HID mode in GestureMouse works plug-and-play with zero host software.
Run this lightweight optional script on macOS, Windows, or Linux if you want:
  - 5 GHz Wi-Fi UDP high-rate cursor/scroll/key receiver (port 5959)
  - Instant Multi-Monitor Teleport (TYPE_TELEPORT = 5)
  - Two-way WebSocket Active-App Auto-Profiles & Quick Host Handoff (port 5960)

Optional desktop dependencies:
    pip install pyautogui pyperclip websockets
"""

import asyncio
import json
import socket
import struct
import sys

UDP_PORT = 5959
WS_PORT = 5960
MAGIC = b"GM"

TYPE_MOUSE = 1
TYPE_KEY_TAP = 2
TYPE_CONSUMER_TAP = 3
TYPE_MACRO = 4
TYPE_TELEPORT = 5

try:
    import pyautogui  # type: ignore
    pyautogui.FAILSAFE = False
except ImportError:
    pyautogui = None

try:
    import pyperclip  # type: ignore
except ImportError:
    pyperclip = None


def verify_xor(packet: bytes) -> bool:
    if len(packet) != 12 or packet[:2] != MAGIC:
        return False
    acc = 0
    for b in packet[:-1]:
        acc ^= b
    return acc == packet[-1]


def handle_udp_packet(packet: bytes) -> None:
    if not verify_xor(packet):
        return
    _seq, pkt_type = struct.unpack_from("<HB", packet, 2)
    p0, p1, p2, p3, p4 = struct.unpack_from("<Bbbbb", packet, 5)

    if pkt_type == TYPE_MOUSE and pyautogui is not None:
        if p0 & 0x80:
            btn = "right" if (p0 & 0x02) else ("middle" if (p0 & 0x04) else "left")
            pyautogui.click(button=btn)
        else:
            if p1 or p2:
                pyautogui.moveRel(p1, p2, _pause=False)
            if p3:
                pyautogui.scroll(p3 * 30)
            if p4:
                pyautogui.hscroll(p4 * 30)
    elif pkt_type == TYPE_TELEPORT:
        monitor_idx = max(1, min(3, p0))
        monitor_count = max(1, min(3, p1))
        if pyautogui is not None:
            screen_w, screen_h = pyautogui.size()
            # Estimate target center across combined virtual desktop width
            target_x = int(((monitor_idx - 0.5) / monitor_count) * screen_w)
            target_y = screen_h // 2
            pyautogui.moveTo(target_x, target_y, _pause=False)
        print(f"[UDP] Teleport -> Display {monitor_idx}/{monitor_count}")


async def udp_loop() -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", UDP_PORT))
    sock.setblocking(False)
    loop = asyncio.get_running_loop()
    print(f"[GestureMouse] Listening for 5 GHz UDP packets on 0.0.0.0:{UDP_PORT}")
    while True:
        data, _addr = await loop.sock_recvfrom(sock, 64)
        handle_udp_packet(data)


async def ws_handler(websocket) -> None:
    print("[GestureMouse] Watch WebSocket connected")
    # Send an initial Active-App Auto-Profile example
    await websocket.send(json.dumps({
        "type": "profile",
        "app": "Browser",
        "category": "browser",
        "crown": "SMOOTH_SCROLL",
        "mode": "MOUSE",
        "macros": [
            {"id": "browser_back", "label": "Back"},
            {"id": "new_tab", "label": "New Tab"},
            {"id": "refresh", "label": "Reload"}
        ]
    }))

    async for raw in websocket:
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            continue
        msg_type = msg.get("type")
        if msg_type == "handoff_pull":
            clip = pyperclip.paste() if pyperclip else ""
            if clip:
                kind = "url" if clip.startswith(("http://", "https://")) else "clipboard"
                await websocket.send(json.dumps({
                    "type": "handoff",
                    "kind": kind,
                    "text": clip[:512]
                }))
        elif msg_type == "handoff_push":
            text = msg.get("text", "")
            if text and pyperclip:
                pyperclip.copy(text)
            print(f"[Handoff] Received from watch: {text[:80]}")
        elif msg_type == "macro":
            print(f"[Macro] Triggered: {msg.get('id')}")


async def main() -> None:
    tasks = [asyncio.create_task(udp_loop())]
    try:
        import websockets  # type: ignore
        ws_server = await websockets.serve(ws_handler, "0.0.0.0", WS_PORT)
        print(f"[GestureMouse] WebSocket Auto-Profile/Handoff server on 0.0.0.0:{WS_PORT}")
        tasks.append(asyncio.create_task(ws_server.wait_closed()))
    except ImportError:
        print("[GestureMouse] 'websockets' not installed; running UDP-only mode.")
    await asyncio.gather(*tasks)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
