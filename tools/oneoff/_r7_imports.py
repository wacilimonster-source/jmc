import io, re, glob

# 修复 R7 FQN 收敛后缺失的 import：BOM 文件 ^package 正则没匹配上，import 没插进去
FIXES = [
    ("rememberCoroutineScope", "import androidx.compose.runtime.rememberCoroutineScope\n"),
    ("remember", "import androidx.compose.runtime.remember\n"),
    ("mutableStateOf", "import androidx.compose.runtime.mutableStateOf\n"),
    ("WindowInsets(0, 0)", "import androidx.compose.foundation.layout.WindowInsets\n"),
    ("TextAlign.Center", "import androidx.compose.ui.text.style.TextAlign\n"),
    ("SnackbarDuration.Long", "import androidx.compose.material3.SnackbarDuration\n"),
    ("SnackbarResult.ActionPerformed", "import androidx.compose.material3.SnackbarResult\n"),
    ("Button(", "import androidx.compose.material3.Button\n"),
    ("Arrangement.", "import androidx.compose.foundation.layout.Arrangement\n"),
    ("AlertDialog(", "import androidx.compose.material3.AlertDialog\n"),
    ("LocalContext.current", "import androidx.compose.ui.platform.LocalContext\n"),
    ("Spacer(", "import androidx.compose.foundation.layout.Spacer\n"),
    ("height(", "import androidx.compose.foundation.layout.height\n"),
    ("Row(", "import androidx.compose.foundation.layout.Row\n"),
    ("Column(", "import androidx.compose.foundation.layout.Column\n"),
    ("Box(", "import androidx.compose.foundation.layout.Box\n"),
    ("fillMaxWidth()", "import androidx.compose.foundation.layout.fillMaxWidth\n"),
    ("fillMaxSize()", "import androidx.compose.foundation.layout.fillMaxSize\n"),
    ("padding(", "import androidx.compose.foundation.layout.padding\n"),
    ("Text(", "import androidx.compose.material3.Text\n"),
    ("MaterialTheme.", "import androidx.compose.material3.MaterialTheme\n"),
    ("delay(", "import kotlinx.coroutines.delay\n"),
]

paths = glob.glob("app/src/main/java/**/*.kt", recursive=True) + glob.glob("app/src/test/java/**/*.kt", recursive=True)
fixed = 0
for path in paths:
    t = io.open(path, encoding="utf-8").read()
    if t.startswith("﻿") or "package" not in t.split("\n")[0].replace("﻿", ""):
        pass
    for usage, imp in FIXES:
        if imp in t:
            continue
        # 短名是否被使用（词边界；排除 FQN 残留与 import/package 行）
        pat = re.compile(r"(?<![A-Za-z0-9_.])" + re.escape(usage.rstrip("(")) + r"(?![A-Za-z0-9_.])")
        body = [l for l in t.splitlines() if not l.strip().startswith(("import ", "package "))]
        if not any(pat.search(l) for l in body):
            continue
        # 插入到第一个 import 行之后
        m = re.search(r"^import [^\n]+\n", t, flags=re.M)
        if not m:
            continue
        t = t[:m.end()] + imp + t[m.end():]
        fixed += 1
    io.open(path, "w", encoding="utf-8", newline="").write(t)
print("imports fixed:", fixed)
