#!/usr/bin/env python3
"""
VR Player Proxy
Strips X-Frame-Options and Content-Security-Policy headers so any site
can be embedded in the browser tab.

Usage:
  python proxy.py          # runs on port 8082
  python proxy.py 9000     # custom port
"""

from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.request, urllib.error, urllib.parse
import http.cookiejar
import gzip, zlib, re, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8082
PROXY = f'http://localhost:{PORT}'

DROP_HEADERS = {
    'x-frame-options',
    'content-security-policy',
    'content-encoding',   # we decompress so re-encoding would be wrong
    'transfer-encoding',
    'content-length',     # we rewrite body so length changes
}

UA = ('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36')

# Persistent cookie jar — survives across pages (age gates, login sessions, etc.)
_jar    = http.cookiejar.CookieJar()
_opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(_jar),
    urllib.request.HTTPRedirectHandler(),
)


class Handler(BaseHTTPRequestHandler):

    def do_GET(self):  self.handle_req()
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        self.handle_req(self.rfile.read(n) if n else None)
    def do_HEAD(self): self.handle_req()

    def target(self):
        p = self.path.lstrip('/')
        return p if p.startswith(('http://', 'https://')) else None

    def handle_req(self, body=None):
        url = self.target()
        if not url:
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(
                b'<p style="font-family:sans-serif;padding:20px">'
                b'<b>VR Proxy running.</b><br>'
                b'Usage: <code>http://localhost:' + str(PORT).encode() +
                b'/https://example.com</code></p>'
            )
            return

        hdrs = {
            'User-Agent': UA,
            'Accept': self.headers.get('Accept', 'text/html,*/*'),
            'Accept-Language': 'en-US,en;q=0.9',
            'Accept-Encoding': 'gzip, deflate',
        }
        if self.headers.get('Cookie'):
            hdrs['Cookie'] = self.headers['Cookie']
        if self.headers.get('Referer'):
            ref = self.headers['Referer']
            hdrs['Referer'] = ref.replace(PROXY + '/', '', 1)

        try:
            req = urllib.request.Request(url, data=body, headers=hdrs,
                                         method='POST' if body else 'GET')
            with _opener.open(req, timeout=20) as resp:
                raw  = resp.read()
                ct   = resp.headers.get('Content-Type', '')
                enc  = resp.headers.get('Content-Encoding', '')
                final_url = resp.url

                # Decompress
                try:
                    data = (gzip.decompress(raw)   if 'gzip'    in enc else
                            zlib.decompress(raw)   if 'deflate' in enc else raw)
                except Exception:
                    data = raw

                # Rewrite HTML
                if 'text/html' in ct:
                    text = data.decode('utf-8', errors='replace')
                    text = rewrite(text, final_url)
                    data = text.encode('utf-8')

                self.send_response(resp.status)
                for k, v in resp.headers.items():
                    if k.lower() not in DROP_HEADERS:
                        self.send_header(k, v)
                self.send_header('Content-Length', len(data))
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                if self.command != 'HEAD':
                    self.wfile.write(data)

        except urllib.error.HTTPError as e:
            self.send_error(e.code, str(e.reason))
        except Exception as e:
            self.send_error(502, str(e))

    def log_message(self, *_): pass   # silent


# Script injected into every HTML page to:
#  1. Route JS navigation back through the proxy
#  2. Mask iframe-detection checks
#  3. Auto-click age-gate "I am over 18" buttons
INJECT = f"""<script>(function(){{
var P='{PROXY}/';
/* ── route location changes through proxy ─────────── */
function proxyUrl(u){{
  if(!u)return u;
  u=String(u);
  if(u.startsWith('http://')||u.startsWith('https://'))return P+u;
  return u;
}}
try{{
  var _loc=Object.getOwnPropertyDescriptor(window,'location');
  ['assign','replace'].forEach(function(m){{
    var orig=location[m].bind(location);
    location[m]=function(u){{orig(proxyUrl(u));}};
  }});
  var _hrefDesc=Object.getOwnPropertyDescriptor(Location.prototype,'href');
  if(_hrefDesc&&_hrefDesc.set){{
    Object.defineProperty(Location.prototype,'href',{{
      get:_hrefDesc.get,
      set:function(u){{_hrefDesc.set.call(this,proxyUrl(u));}}
    }});
  }}
}}catch(e){{}}
try{{
  ['pushState','replaceState'].forEach(function(m){{
    var orig=history[m].bind(history);
    history[m]=function(s,t,u){{orig(s,t,u&&proxyUrl(u)||u);}};
  }});
}}catch(e){{}}
try{{
  var origOpen=window.open;
  window.open=function(u,n,f){{return origOpen(proxyUrl(u),n,f);}};
}}catch(e){{}}
/* ── mask iframe detection ────────────────────────── */
try{{Object.defineProperty(window,'frameElement',{{get:function(){{return null;}}}});}}catch(e){{}}
try{{Object.defineProperty(window,'parent',{{get:function(){{return window;}}}});}}catch(e){{}}
try{{Object.defineProperty(window,'top',{{get:function(){{return window;}}}});}}catch(e){{}}
/* ── auto-click age gates ─────────────────────────── */
function clickAgeGate(){{
  var els=[].slice.call(document.querySelectorAll('button,[role=button],a.btn,.btn,input[type=button],input[type=submit]'));
  for(var i=0;i<els.length;i++){{
    var t=(els[i].textContent||els[i].value||'').toLowerCase();
    if(/over.?18|i am|enter site|yes.*adult|confirm age|agree/.test(t)){{els[i].click();return;}}
  }}
}}
document.addEventListener('DOMContentLoaded',function(){{
  setTimeout(clickAgeGate,600);setTimeout(clickAgeGate,2000);
}});
setTimeout(clickAgeGate,1200);
}})();</script>"""


def to_abs(url, base):
    url = url.strip()
    if not url or url.startswith(('data:', 'javascript:', 'mailto:', '#', 'blob:')):
        return None
    if url.startswith('//'): return f'{base.scheme}:{url}'
    if url.startswith('/'): return f'{base.scheme}://{base.netloc}{url}'
    if url.startswith(('http://', 'https://')): return url
    stem = base.path.rsplit('/', 1)[0]
    return f'{base.scheme}://{base.netloc}{stem}/{url}'


def rewrite(html, page_url):
    base = urllib.parse.urlparse(page_url)

    def fix(m):
        attr, q, val = m.group(1), m.group(2), m.group(3)
        a = to_abs(val, base)
        return f'{attr}={q}{PROXY}/{a}{q}' if a else m.group(0)

    html = re.sub(
        r'(href|src|action|data-src|data-href)\s*=\s*(["\'])([^"\']*)\2',
        fix, html, flags=re.IGNORECASE)

    # Inject base tag + navigation intercept script right after <head>
    head_inject = (f'<base href="{PROXY}/{base.scheme}://{base.netloc}/">'
                   + INJECT)
    html = re.sub(r'(<head[^>]*>)', r'\1' + head_inject, html,
                  count=1, flags=re.IGNORECASE)

    # Fallback: no <head> tag — prepend to body
    if '<head' not in html.lower():
        html = head_inject + html

    return html


if __name__ == '__main__':
    srv = HTTPServer(('', PORT), Handler)
    print(f'✓  VR Proxy  →  http://localhost:{PORT}')
    print(f'   Tap 🔓 in the app to route the browser through this proxy.')
    print(f'   Ctrl+C to stop.\n')
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print('Stopped.')
