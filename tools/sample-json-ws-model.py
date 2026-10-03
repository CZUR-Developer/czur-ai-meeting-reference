#!/usr/bin/env python3
"""P5-2 本机样例模型：收到 PCM 后按第二种 JSON 格式返回固定字幕。"""

import argparse
import asyncio
import json

from websockets.asyncio.server import serve


def transcript(revision: int, final: bool) -> str:
    return json.dumps({
        "type": "transcript", "segment": "s1", "revision": revision,
        "text": "这是一段示例字幕" if final else "这是一段示例",
        "final": final, "startMs": 0, "endMs": 500,
        "language": "zh-CN",
    }, ensure_ascii=False)


async def session(socket):
    started = False
    frames = 0
    finalized = False
    async for message in socket:
        if isinstance(message, str):
            kind = json.loads(message).get("type")
            if kind == "start":
                started = True
                await socket.send('{"type":"ready"}')
            elif kind == "stop":
                if frames and not finalized:
                    await socket.send(transcript(2, True))
                await socket.send('{"type":"finished"}')
                return
        elif started:
            frames += 1
            if frames == 1:
                await socket.send(transcript(1, False))
            elif frames == 3:
                await socket.send(transcript(2, True))
                finalized = True


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=19091)
    args = parser.parse_args()
    async with serve(session, args.host, args.port):
        print(f"sample-json-ws listening on {args.host}:{args.port}", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
