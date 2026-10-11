"""Reconstruct SVG previews from shipped VectorDrawable XML for visual QA.

This checks the converted output, rather than reusing Antigravity's previews.
Run after generate_vectors.py, then render_icons.cjs using Node + sharp.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/branding'
OUT.mkdir(parents=True, exist_ok=True)
A = '{http://schemas.android.com/apk/res/android}'
S = 'http://www.w3.org/2000/svg'
ET.register_namespace('', S)

def svg_layer(name):
    vector = ET.parse(ROOT / 'app/src/main/res/drawable' / f'{name}.xml').getroot()
    svg = ET.Element('{'+S+'}svg', {'viewBox': '0 0 108 108', 'width': '108', 'height': '108'})
    defs = ET.SubElement(svg, 'defs')
    def walk(src, dst):
        for e in src:
            if e.tag == 'group':
                px, py = e.get(A+'pivotX', '0'), e.get(A+'pivotY', '0')
                transform = f"translate({e.get(A+'translateX', '0')},{e.get(A+'translateY', '0')}) translate({px},{py}) scale({e.get(A+'scaleX', '1')},{e.get(A+'scaleY', '1')}) translate({-float(px)},{-float(py)})"
                g = ET.SubElement(dst, 'g', {'transform': transform})
                walk(e, g)
                continue
            assert e.tag == 'path'
            p = ET.SubElement(dst, 'path', {'d': e.attrib[A+'pathData'], 'fill': 'none'})
            for ak, sk in [('fillColor', 'fill'), ('strokeColor', 'stroke'), ('fillAlpha', 'fill-opacity'), ('strokeAlpha', 'stroke-opacity'), ('strokeWidth', 'stroke-width'), ('strokeLineCap', 'stroke-linecap'), ('strokeLineJoin', 'stroke-linejoin')]:
                if A+ak in e.attrib:
                    p.set(sk, e.attrib[A+ak])
            for item in e:
                paint = item.attrib['name'].removeprefix('android:').removesuffix('Color')
                grad = item[0]
                gid = name+str(len(defs))
                values = {'id': gid, 'gradientUnits': 'userSpaceOnUse'}
                if grad.attrib[A+'type'] == 'linear':
                    tag = 'linearGradient'
                    for ak, sk in [('startX', 'x1'), ('startY', 'y1'), ('endX', 'x2'), ('endY', 'y2')]:
                        values[sk] = grad.attrib[A+ak]
                else:
                    tag = 'radialGradient'
                    for ak, sk in [('centerX', 'cx'), ('centerY', 'cy'), ('gradientRadius', 'r')]:
                        values[sk] = grad.attrib[A+ak]
                sg = ET.SubElement(defs, tag, values)
                for stop in grad:
                    c = stop.attrib[A+'color']
                    ET.SubElement(sg, 'stop', {'offset': stop.attrib[A+'offset'], 'stop-color': '#'+c[3:], 'stop-opacity': str(int(c[1:3], 16)/255)})
                p.set(paint, f'url(#{gid})')
    walk(vector, svg)
    return svg

for name in ('ic_launcher_foreground', 'ic_launcher_background', 'ic_launcher_monochrome'):
    ET.ElementTree(svg_layer(name)).write(OUT / f'{name}.svg')

composite = svg_layer('ic_launcher_background')
for e in svg_layer('ic_launcher_foreground'):
    composite.append(e)
ET.ElementTree(composite).write(OUT / 'composite.svg')
