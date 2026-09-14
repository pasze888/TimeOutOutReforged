#!/usr/bin/env python3
"""TimeOutOut 登录超时快速探针。

只发送一个握手包（intent=LOGIN）后保持沉默：服务端在收到握手包时即创建
ServerLoginPacketListenerImpl 并开始 tick，因此无需完成登录（甚至不用发
Login Start）就能触发 ServerLoginPacketListenerImplMixin 的登录超时踢人。

用法：
    python login_timeout_probe.py [host] [port] [--protocol 767] [--wait 30]

前置条件：
    loginTimeoutTicks 已调小（如 60 = 3 秒），且 readTimeoutSeconds 大于它。
"""

import argparse
import socket
import struct
import time


def varint(value):
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        out.append(b | 0x80 if value else b)
        if not value:
            return bytes(out)


def read_varint(buf, pos):
    result = 0
    shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, pos
        shift += 7


def decode_frame(buf):
    """把收到的字节流解成最终断线原因；数据不够时返回 None。"""
    frame_len, pos = read_varint(buf, 0)
    if len(buf) - pos < frame_len:
        return None
    payload = buf[pos:pos + frame_len]
    packet_id, p = read_varint(payload, 0)
    if packet_id != 0x00:
        return "packet 0x%02x" % packet_id
    slen, p = read_varint(payload, p)
    return payload[p:p + slen].decode("utf-8", "replace")


def main():
    parser = argparse.ArgumentParser(description="TimeOutOut login timeout probe")
    parser.add_argument("host", nargs="?", default="127.0.0.1")
    parser.add_argument("port", nargs="?", type=int, default=25565)
    parser.add_argument("--protocol", type=int, default=767,
                        help="Minecraft protocol version (1.21.1 = 767)")
    parser.add_argument("--wait", type=float, default=30.0,
                        help="seconds to wait for the kick")
    args = parser.parse_args()

    host_bytes = args.host.encode("utf-8")
    handshake = (varint(0x00) + varint(args.protocol) + varint(len(host_bytes))
                 + host_bytes + struct.pack(">H", args.port) + varint(2))
    payload = varint(len(handshake)) + handshake

    with socket.create_connection((args.host, args.port), timeout=10) as sock:
        start = time.monotonic()
        sock.sendall(payload)
        print("handshake (intent=LOGIN) sent to %s:%d, staying silent..."
              % (args.host, args.port))

        sock.settimeout(args.wait)
        buf = b""
        reason = None
        try:
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    print("kicked after %.2fs (TCP closed without reason)"
                          % (time.monotonic() - start))
                    return
                buf += chunk
                reason = decode_frame(buf)
                if reason is not None:
                    break
        except socket.timeout:
            print("NO disconnect after %.0fs -- loginTimeoutTicks 要小于 readTimeoutSeconds"
                  % args.wait)
            return

        print("kick reason after %.2fs: %s" % (time.monotonic() - start, reason))

        sock.settimeout(5)
        try:
            while sock.recv(4096):
                pass
            print("server closed the connection")
        except socket.timeout:
            print("server kept the connection open")


if __name__ == "__main__":
    main()
