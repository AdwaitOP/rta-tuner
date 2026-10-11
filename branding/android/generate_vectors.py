"""Convert the selected final SVGs to native Android vectors (Python stdlib only).

Supports the geometry used by these assets. SVG filter shadows are deliberately
omitted; objectBoundingBox gradients are resolved to viewport coordinates.
The editable Antigravity originals are never modified.
"""
from pathlib import Path
import math
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = 'http://schemas.android.com/apk/res/android'
AAPT = 'http://schemas.android.com/aapt'
ET.register_namespace('android', ANDROID)
ET.register_namespace('aapt', AAPT)

def attr(name):
    return '{' + ANDROID + '}' + name

def path_data(e):
    tag = e.tag.split('}')[-1]
    if tag == 'path':
        return ' '.join(e.attrib['d'].split())
    if tag == 'circle':
        x, y, r = (float(e.attrib[k]) for k in ('cx', 'cy', 'r'))
        return f'M {x-r},{y} a {r},{r} 0 1,0 {2*r},0 a {r},{r} 0 1,0 {-2*r},0 Z'
    if tag == 'rect':
        w, h = e.attrib['width'], e.attrib['height']
        return f'M 0,0 H {w} V {h} H 0 Z'
    raise ValueError(tag)

def bounds(e):
    tag = e.tag.split('}')[-1]
    if tag == 'rect':
        return 0, 0, float(e.attrib['width']), float(e.attrib['height'])
    if tag == 'circle':
        x, y, r = (float(e.attrib[k]) for k in ('cx', 'cy', 'r'))
        return x-r, y-r, x+r, y+r
    tokens = re.findall(r'[MCLZ]|[-+]?\d*\.?\d+', e.attrib['d'])
    pts, i, current = [], 0, (0, 0)
    while i < len(tokens):
        command = tokens[i]
        i += 1
        if command == 'Z':
            continue
        count = 6 if command == 'C' else 2
        values = list(map(float, tokens[i:i+count]))
        i += count
        if command in ('M', 'L'):
            current = tuple(values)
            pts.append(current)
        elif command == 'C':
            p0, p1, p2, p3 = current, values[:2], values[2:4], values[4:]
            ts = {0.0, 1.0}
            for axis in (0, 1):
                a = -p0[axis]+3*p1[axis]-3*p2[axis]+p3[axis]
                b = 2*(p0[axis]-2*p1[axis]+p2[axis])
                c = p1[axis]-p0[axis]
                if abs(a) < 1e-12:
                    roots = [-c/b] if abs(b) > 1e-12 else []
                else:
                    disc = b*b-4*a*c
                    roots = [(-b+math.sqrt(disc))/(2*a), (-b-math.sqrt(disc))/(2*a)] if disc >= 0 else []
                ts.update(t for t in roots if 0 < t < 1)
            for t in ts:
                pts.append(tuple((1-t)**3*p0[k]+3*(1-t)**2*t*p1[k]+3*(1-t)*t*t*p2[k]+t**3*p3[k] for k in (0, 1)))
            current = tuple(p3)
        else:
            raise ValueError(command)
    return min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts)

def color(hex_color, opacity=1):
    return f'#{round(float(opacity)*255):02X}{hex_color.lstrip("#")}'

def convert(name):
    source = ET.parse(ROOT / 'branding/final' / f'{name}.svg').getroot()
    defs = {e.attrib['id']: e for e in source.iter() if 'id' in e.attrib}
    vector = ET.Element('vector', {attr('width'): '108dp', attr('height'): '108dp', attr('viewportWidth'): '108', attr('viewportHeight'): '108'})
    # The supplied underfill reaches just beyond r=33. Keep all visible geometry
    # within the guaranteed safe circle with a 2% optical size adjustment.
    target = vector
    if name != 'ic_launcher_background':
        target = ET.SubElement(vector, 'group', {attr('pivotX'): '54', attr('pivotY'): '54', attr('scaleX'): '0.98', attr('scaleY'): '0.98'})
    def walk(src, dst):
        for e in src:
            tag = e.tag.split('}')[-1]
            if tag == 'defs':
                continue
            if tag == 'g':
                x, y = map(float, re.findall(r'[-+]?\d*\.?\d+', e.attrib['transform']))
                g = ET.SubElement(dst, 'group', {attr('translateX'): str(x), attr('translateY'): str(y)})
                walk(e, g)
                continue
            p = ET.SubElement(dst, 'path', {attr('pathData'): path_data(e)})
            for svg_key, android_key in [('stroke-width', 'strokeWidth'), ('stroke-linecap', 'strokeLineCap'), ('stroke-linejoin', 'strokeLineJoin')]:
                if svg_key in e.attrib:
                    p.set(attr(android_key), e.attrib[svg_key])
            for paint in ('fill', 'stroke'):
                value = e.attrib.get(paint, 'none')
                if value == 'none':
                    continue
                if 'opacity' in e.attrib:
                    p.set(attr(paint+'Alpha'), e.attrib['opacity'])
                if not value.startswith('url'):
                    p.set(attr(paint+'Color'), value)
                    continue
                grad = defs[value[5:-1]]
                x0, y0, x1, y1 = bounds(e)
                def coord(key, start, end):
                    return str(start+float(grad.attrib[key].rstrip('%'))/100*(end-start))
                item = ET.SubElement(p, '{'+AAPT+'}attr', {'name': 'android:'+paint+'Color'})
                if grad.tag.endswith('linearGradient'):
                    ga = {attr('type'): 'linear', attr('startX'): coord('x1', x0, x1), attr('startY'): coord('y1', y0, y1), attr('endX'): coord('x2', x0, x1), attr('endY'): coord('y2', y0, y1)}
                else:
                    ga = {attr('type'): 'radial', attr('centerX'): coord('cx', x0, x1), attr('centerY'): coord('cy', y0, y1), attr('gradientRadius'): str(float(grad.attrib['r'].rstrip('%'))/100*(x1-x0))}
                gradient = ET.SubElement(item, 'gradient', ga)
                for stop in grad:
                    ET.SubElement(gradient, 'item', {attr('offset'): str(float(stop.attrib['offset'].rstrip('%'))/100), attr('color'): color(stop.attrib['stop-color'], stop.attrib.get('stop-opacity', 1))})
    walk(source, target)
    ET.indent(vector, space='    ')
    ET.ElementTree(vector).write(ROOT / 'app/src/main/res/drawable' / f'{name}.xml', encoding='utf-8', xml_declaration=True)

if __name__ == '__main__':
    for name in ('ic_launcher_foreground', 'ic_launcher_background', 'ic_launcher_monochrome'):
        convert(name)
