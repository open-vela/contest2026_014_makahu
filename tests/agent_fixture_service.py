"""Synthetic local OpenAI-compatible HTTPS fixture for actual Vela firmware.
No production credentials or microphone data. Never use this CA in production.
"""
import base64
import http.server
import json
import time

state = {"requests": [], "bad_link": False, "probe": "normal"}


class Service(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_POST(self):
        assert self.path == "/v1/chat/completions"
        assert self.headers.get("Authorization") == "Bearer fixture-secret"
        length = int(self.headers["Content-Length"])
        assert length < 768 * 1024
        body = json.loads(self.rfile.read(length))
        state["requests"].append(body)
        if state.get("auth_fail"):
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers(); self.close_connection = True
            return
        if "model" not in body:
            payload = b'{"probe":true}'
        elif body["model"] == "mimo-v2.5-asr":
            uri = body["messages"][0]["content"][0]["input_audio"]["data"]
            assert uri.startswith("data:audio/wav;base64,")
            audio = base64.b64decode(uri.split(",", 1)[1], validate=True)
            assert audio[:4] == b"RIFF" and audio[8:12] == b"WAVE"
            text = "" if not any(audio[44:]) else "Buffer demo follow-up"
            payload = self.choice({"role": "assistant", "content": text})
        else:
            assert body["model"] == "mimo-v2.5"
            messages = body["messages"]
            if not body.get("tools"):
                payload = self.choice({"role": "assistant", "content": "OK"})
            else:
                assert messages[0]["role"] == "system"
                offered = {tool["function"]["name"] for tool in body["tools"]}
                facts = json.loads(next(m["content"] for m in messages if m["role"] == "user"))
                common = {"search_records", "read_record"}
                assistant_tools = {"focus_session", "device_timer", "device_alarm", "show_translation", "show_recipe", "show_answer", "ask_user", "image_search"}
                assert common <= offered, "read-only record tools missing"
                if facts.get("target") in ("assistant", "auto"):
                    assert assistant_tools <= offered and offered <= common | assistant_tools, "assistant tool schema mismatch"
                else:
                    assert not offered & assistant_tools and offered == common, "Buffer received assistant side-effect tools"
                assert "文本和录音转写使用相同规则" in messages[0]["content"]
                tools = [m for m in messages if m["role"] == "tool"]
                assistant = state.get("assistant_case")
                forced = state.get("force_unoffered_tool")
                batch = state.get("assistant_tool_batch")
                if forced and facts.get("target") == "buffer" and not tools:
                    state["force_unoffered_tool"] = None
                    payload = self.tool(forced[0], forced[1], "unoffered1")
                elif batch and assistant and not tools:
                    payload = self.tool_batch(batch)
                elif assistant and not tools:
                    name, arguments = assistant
                    payload = self.tool(name, arguments, "device1")
                elif assistant:
                    if state.get("assistant_image_pipeline") and len(tools) == 1:
                        found = json.loads(tools[-1]["content"])
                        assert found["success"] and len(found["images"]) == 1
                        payload = self.tool("show_recipe", {"title": "海带红烧肉", "ingredients": "海带、五花肉", "image_id": found["images"][0]["image_id"], "steps": [{"title": "炖煮", "detail": "小火炖煮", "timer_ms": 60000}]}, "recipe-image")
                    elif state.get("assistant_timeout_followup_once"):
                        state["assistant_timeout_followup_once"] = False
                        time.sleep(3.5)  # Exceed the production fixture's 3 s read timeout.
                        return
                    elif state.get("assistant_malformed_followup_once"):
                        state["assistant_malformed_followup_once"] = False
                        payload = self.choice({"role": "assistant", "content": "{malformed"})
                    else:
                        reply = "Local tool returned its actual result."
                        if state.get("nested_assistant_response"):
                            reply = json.dumps({"assistant_response": reply})
                        payload = self.choice({"role": "assistant", "content": json.dumps({"assistant_response": reply})})
                elif not tools:
                    payload = self.tool("search_records", {"query": "Buffer"}, "search1")
                else:
                    result = json.loads(tools[-1]["content"])
                    if result.get("records"):
                        payload = self.tool("read_record", {"card_id": result["records"][0]["card_id"]}, "read1")
                    else:
                        related = [result["card_id"]] if "card_id" in result else []
                        if state["bad_link"]:
                            related = ["invented-card"]
                        answer = {"segments": [{
                            "text": facts["text"], "kind": "task_card",
                            "title": "Buffer demo", "summary": facts["text"],
                            "confidence": 0.9, "health_category": None,
                            "related_card_ids": related,
                            "presentation": {"blocks": [{
                                "type": "paragraph", "title": "Next", "text": facts["text"],
                            }]},
                        }]}
                        payload = self.choice({"role": "assistant", "content": json.dumps(answer)})
        probe = state["probe"]
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        keepalive = probe in ("keepalive", "chunked_keepalive", "fragmented_keepalive")
        self.send_header("Connection", "keep-alive" if keepalive else "close")
        if probe in ("chunked", "chunked_keepalive"):
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for part in (payload[:7], payload[7:]):
                self.wfile.write(f"{len(part):x}\r\n".encode() + part + b"\r\n")
            self.wfile.write(b"0\r\n\r\n")
        else:
            self.send_header("Content-Length", str(len(payload) + (7 if probe == "truncated" else 0)))
            self.end_headers()
            if probe == "fragmented_keepalive":
                for byte in payload:
                    self.wfile.write(bytes([byte]))
                    self.wfile.flush()
            else:
                self.wfile.write(payload)
        self.wfile.flush()
        self.close_connection = not keepalive

    @staticmethod
    def choice(message, finish="stop"):
        return json.dumps({"choices": [{"message": message, "finish_reason": finish}]}).encode()

    def tool(self, name, arguments, call_id):
        return self.choice({"role": "assistant", "content": None, "tool_calls": [{
            "id": call_id, "type": "function",
            "function": {"name": name, "arguments": json.dumps(arguments)},
        }]}, "tool_calls")

    def tool_batch(self, calls):
        return self.choice({"role": "assistant", "content": None, "tool_calls": [
            {"id": f"batch{index}", "type": "function",
             "function": {"name": name, "arguments": json.dumps(arguments)}}
            for index, (name, arguments) in enumerate(calls)
        ]}, "tool_calls")



def main():
    import argparse
    from pathlib import Path
    import ssl
    import subprocess
    import os
    import math
    import struct
    import wave
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--port', type=int, default=18443)
    a = p.parse_args()
    a.output.mkdir(mode=0o700, parents=True, exist_ok=True)
    def openssl(*args):
        subprocess.run(['openssl', *map(str,args)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    key, ca = a.output/'ca.key', a.output/'ca.pem'
    if key.exists() or ca.exists():
        raise SystemExit('Use a NEW fixture directory; existing CA material is never overwritten')
    openssl('req','-x509','-newkey','rsa:2048','-nodes','-keyout',key,'-out',ca,
            '-days','2','-subj','/CN=Buffer local fixture CA','-addext','basicConstraints=critical,CA:TRUE')
    serverkey, csr, cert = a.output/'server.key', a.output/'server.csr', a.output/'server.pem'
    openssl('req','-newkey','rsa:2048','-nodes','-keyout',serverkey,'-out',csr,'-subj','/CN=localhost')
    extensions = a.output/'extensions.cnf'
    extensions.write_text('subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2\nextendedKeyUsage=serverAuth\n')
    openssl('x509','-req','-in',csr,'-CA',ca,'-CAkey',key,'-CAcreateserial','-out',cert,
            '-days','2','-extfile',extensions)
    for path in (key,serverkey): path.chmod(0o600)
    profile={'type':'vela.oobe.request','protocol':1,'request_id':'b'*32,'revision':2,
             'operation':'agent.configure','payload':{'base_url':f'https://10.0.2.2:{a.port}/v1',
             'completion_model':'mimo-v2.5','transcription_model':'mimo-v2.5-asr',
             'api_key':'fixture-secret','time_zone':'Asia/Shanghai','language':'zh-CN'}}
    (a.output/'profile.json').write_text(json.dumps(profile)); (a.output/'profile.json').chmod(0o600)
    with wave.open(str(a.output/'fixture-voice.wav'),'wb') as audio:
        audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000)
        audio.writeframes(b''.join(struct.pack('<h',int(1200*math.sin(i/8))) for i in range(4000)))
    (a.output/'fixture-voice.meta').write_text('created_at=1700000002000\nduration_ms=250\nmode=normal\nstate=queued\n')
    (a.output/'fixture-voice.wav').chmod(0o600)
    service=http.server.ThreadingHTTPServer(('127.0.0.1',a.port),Service)
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,serverkey)
    service.socket=context.wrap_socket(service.socket,server_side=True)
    print(f'Local synthetic HTTPS fixture listening on port {a.port}; private files in {a.output}',flush=True)
    print('Test firmware only: CONFIG_BUFFER_VELA_AGENT_CA_FILE="/fixture/ca.pem"; mount fixture source at /fixture',flush=True)
    try: service.serve_forever()
    finally: service.server_close()

if __name__=='__main__':main()
