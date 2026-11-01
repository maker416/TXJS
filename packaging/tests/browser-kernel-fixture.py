#!/usr/bin/env python3
# Copyright 2023-2025 RWPP contributors
# 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
# Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
# https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
"""Local-only HTTP fixture for the independent Android host (adb reverse tcp:8765 tcp:8765)."""
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
import argparse

class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith('/page') or self.path.startswith('/sso/client'):
            page = b'''<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1"><body><h1>External Gecko loaded</h1>
<a href="/protected.rwmod">Download</a><form style="position:absolute;left:0;top:100px" method="post" action="/upload" enctype="multipart/form-data"><input name="file" type="file" onclick="fetch('/clicked')" onchange="this.form.submit()"></form>
<script>document.cookie='kernel_test=authenticated; path=/';window.rwForumClient=function(ticket){fetch('/handoff?ticket='+ticket)};fetch('/loaded');</script>'''
            self.send_response(200); self.send_header('Content-Type','text/html'); self.send_header('Content-Length',str(len(page))); self.end_headers(); self.wfile.write(page)
        elif self.path == '/protected.rwmod':
            self.send_response(200 if 'kernel_test=authenticated' in self.headers.get('Cookie','') else 403)
            self.send_header('Content-Type','application/octet-stream'); self.send_header('Content-Disposition','attachment; filename=protected.rwmod'); self.send_header('Content-Length','4'); self.end_headers(); self.wfile.write(bytes([1,2,3,4]))
        elif self.path.startswith(('/loaded', '/handoff', '/clicked')):
            self.send_response(200); self.send_header('Content-Length','2'); self.end_headers(); self.wfile.write(b'OK')
        else: super().do_GET()
    def do_POST(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        print('UPLOAD_CONTENT_VERIFIED', b'external-kernel-upload' in body, flush=True)
        authenticated='kernel_test=authenticated' in self.headers.get('Cookie','')
        print('UPLOAD_COOKIE_VERIFIED', authenticated, flush=True)
        self.send_response(200 if authenticated else 403); self.send_header('Content-Length','2'); self.end_headers(); self.wfile.write(b'OK')

if __name__ == '__main__':
    p=argparse.ArgumentParser(); p.add_argument('--root',required=True); p.add_argument('--port',type=int,default=8765); a=p.parse_args()
    root=Path(a.root).resolve()
    ThreadingHTTPServer(('127.0.0.1',a.port),lambda *args,**kw: Handler(*args,directory=str(root),**kw)).serve_forever()
