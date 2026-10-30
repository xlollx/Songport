package com.xlollx.songport.server

import java.text.SimpleDateFormat
import java.util.Date

/** Small pieces of HTML for the server's pages: no template engine, no script beyond what a page needs. */
object Html {
    fun h(s: String?): String = (s ?: "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    fun date(epoch: Long): String = if (epoch <= 0) "never" else SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date(epoch))

    /** A button that posts to [action]: every change goes through POST, never a link. */
    fun postButton(action: String, label: String, style: String = "", fields: Map<String, String> = emptyMap(), confirm: String? = null): String =
        """<form method="post" action="${h(action)}" class="inline"${confirm?.let { " onsubmit=\"return confirm('${h(it)}')\"" } ?: ""}>""" +
            fields.entries.joinToString("") { (k, v) -> """<input type="hidden" name="${h(k)}" value="${h(v)}">""" } +
            """<button class="${h(style)}">${h(label)}</button></form>"""

    /** Where the engine runs: in a container ([SERVER]) or as an app on the user's computer ([DESKTOP]). */
    enum class Edition(val product: String, val guide: String) {
        SERVER("Trackhop server", "https://xlollx.github.io/Trackhop/guide/docker"),
        DESKTOP("Trackhop Desktop", "https://xlollx.github.io/Trackhop/guide/desktop"),
    }

    fun page(title: String, body: String, version: String, refreshSeconds: Int? = null, edition: Edition = Edition.SERVER): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
${refreshSeconds?.let { """<meta http-equiv="refresh" content="$it">""" } ?: ""}
<title>${h(title)} · Trackhop</title>
<link rel="stylesheet" href="/style.css">
</head><body>
<header><a class="brand" href="/">Trackhop <span class="alpha">alpha</span></a><nav><a href="/">Syncs</a><a href="/connectors">Services</a><a href="/tools">Tools</a><a href="/log">Log</a></nav></header>
<p class="alpha-note"><strong>Alpha.</strong> ${h(edition.product)} is an early test version: things can break or change between releases. Keep a copy of the data folder and <a href="https://xlollx.github.io/Trackhop/guide/reporting-a-problem">report what goes wrong</a>.</p>
<main>
<h1>${h(title)}</h1>
$body
</main>
<footer>${h(edition.product)} ${h(version)} (alpha) · <a href="${edition.guide}">Guide</a> · <a href="https://github.com/xlollx/Trackhop">GitHub</a>${if (edition == Edition.DESKTOP) " · " + postButton("/quit", "Quit Trackhop", "link", confirm = "Stop Trackhop? Scheduled syncs run only while it is running.") else ""}</footer>
</body></html>"""

    const val CSS = """
:root{--bg:#fbf8ff;--surface:#fff;--surface-2:#f0ecf7;--line:#e2dcec;--ink:#1c1b22;--ink-2:#49454f;--ink-3:#6e6878;--accent:#5b3fd9;--accent-ink:#fff;--link:#4c32c4;--ok:#1b7f4b;--err:#b3261e;--radius:14px;color-scheme:light}
@media (prefers-color-scheme:dark){:root{--bg:#131218;--surface:#1c1b22;--surface-2:#25232d;--line:#34313d;--ink:#e6e1ea;--ink-2:#c9c4d0;--ink-3:#9a94a3;--accent:#c8bfff;--accent-ink:#2b1680;--link:#c8bfff;--ok:#7dd8a3;--err:#ffb4ab;color-scheme:dark}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
header{display:flex;flex-wrap:wrap;gap:12px 20px;align-items:center;padding:14px 16px;border-bottom:1px solid var(--line);background:var(--surface)}
header nav{display:flex;gap:16px;flex-wrap:wrap}
a{color:var(--link)}
.brand{font-weight:700;font-size:18px;text-decoration:none;background:linear-gradient(120deg,#4338ca,#7c3aed 50%,#db2777);-webkit-background-clip:text;background-clip:text;color:transparent}
main{max-width:920px;margin:0 auto;padding:16px;overflow-wrap:anywhere}
h1{font-size:24px;margin:8px 0 16px}
h2{font-size:18px;margin:24px 0 8px}
.card{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:14px 16px;margin:0 0 12px}
.row{display:flex;flex-wrap:wrap;gap:8px;align-items:center;justify-content:space-between}
.muted{color:var(--ink-3);font-size:13px}
.ok{color:var(--ok)}.err{color:var(--err)}
.alpha{display:inline-block;font-size:11px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;padding:1px 7px;margin-left:6px;border-radius:999px;background:var(--err);color:var(--surface);vertical-align:middle}
.alpha-note{margin:0;padding:10px 16px;background:var(--surface-2);border-bottom:1px solid var(--line);color:var(--ink-2);font-size:14px}
.badge{display:inline-block;font-size:12px;padding:1px 8px;border-radius:999px;background:var(--surface-2);color:var(--ink-2)}
button,.button{font:inherit;cursor:pointer;border:0;border-radius:999px;padding:7px 16px;background:var(--accent);color:var(--accent-ink);text-decoration:none;display:inline-block}
button.secondary,.button.secondary{background:var(--surface-2);color:var(--ink)}
button.danger{background:transparent;color:var(--err);border:1px solid var(--line)}
form.inline{display:inline}
button.link{background:none;padding:0;border-radius:0;color:var(--link);text-decoration:underline;font-size:inherit}
label{display:block;margin:10px 0 4px;font-weight:600;font-size:14px}
input[type=text],input[type=password],input[type=url],select,textarea{width:100%;font:inherit;padding:8px 10px;border-radius:10px;border:1px solid var(--line);background:var(--surface);color:var(--ink)}
.check{display:flex;gap:8px;align-items:center;font-weight:400}
pre{white-space:pre-wrap;word-break:break-word;font-size:12.5px;background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);padding:12px;overflow:auto}
code{background:var(--surface-2);padding:1px 6px;border-radius:6px;word-break:break-all}
ol li{margin:4px 0}
footer{max-width:920px;margin:24px auto;padding:0 16px 24px;color:var(--ink-3);font-size:13px}
.actions{display:flex;gap:6px;flex-wrap:wrap}
progress{width:100%}
"""
}
