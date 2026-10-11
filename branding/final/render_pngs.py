import os
import subprocess
from PIL import Image

FINAL_DIR = os.path.abspath(r"branding/final")
EDGE_EXE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

# 1. Read master SVG
master_svg_path = os.path.join(FINAL_DIR, "cabinrta-master.svg")
with open(master_svg_path, "r", encoding="utf-8") as f:
    svg_content = f.read()

# 2. Create HTML wrapper for 512x512
wrapper_html = f"""<!DOCTYPE html>
<html>
<head>
  <style>
    * {{ margin: 0; padding: 0; box-sizing: border-box; }}
    html, body {{ width: 512px; height: 512px; overflow: hidden; background: #000000; }}
    svg {{ width: 512px; height: 512px; display: block; }}
  </style>
</head>
<body>
{svg_content}
</body>
</html>"""

wrapper_path = os.path.join(FINAL_DIR, "export_wrapper.html")
with open(wrapper_path, "w", encoding="utf-8") as f:
    f.write(wrapper_html)

# 3. Export 512x512 PNG via Headless Edge
out_512_png = os.path.join(FINAL_DIR, "ic_launcher-playstore-512.png")
cmd = [
    EDGE_EXE,
    "--headless",
    "--disable-gpu",
    f"--screenshot={out_512_png}",
    "--window-size=512,512",
    "--hide-scrollbars",
    f"file:///{wrapper_path.replace(os.sep, '/')}"
]

print("Running Edge export...")
subprocess.run(cmd, check=True)

# 4. Verify with PIL and crop if needed (Edge window screenshot is exact 512x512)
if os.path.exists(out_512_png):
    im = Image.open(out_512_png)
    print(f"Exported PNG size: {im.size}, mode: {im.mode}")
    if im.size != (512, 512):
        im = im.crop((0, 0, 512, 512))
        im.save(out_512_png, "PNG", optimize=True)
        print("Cropped to exact 512x512")
    else:
        im.save(out_512_png, "PNG", optimize=True)
    
    file_size_kb = os.path.getsize(out_512_png) / 1024
    print(f"Verified Google Play Store PNG: {out_512_png} ({file_size_kb:.1f} KB)")

# Clean temporary wrapper
if os.path.exists(wrapper_path):
    os.remove(wrapper_path)

print("Export completed successfully.")
