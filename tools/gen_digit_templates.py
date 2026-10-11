#!/usr/bin/env python3
"""Genera core/.../ocr/DigitTemplates.kt: plantillas de dígitos 0-9 normalizadas.

Cada plantilla es la imagen del dígito recortada a su caja, reducida a
W x H con promediado de área y cuantizada a 16 niveles (un carácter hex por
píxel). Se usan varias tipografías habituales en patrones (sans y serif).
Uso: python3 tools/gen_digit_templates.py
"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 12, 16
FONTS = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/opentype/inter/Inter-Regular.otf",
    "/usr/share/fonts/truetype/freefont/FreeSans.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf",
]

def render(font_path, ch):
    font = ImageFont.truetype(font_path, 96)
    img = Image.new("L", (160, 160), 0)
    d = ImageDraw.Draw(img)
    d.text((30, 10), ch, fill=255, font=font)
    bbox = img.point(lambda v: 255 if v > 100 else 0).getbbox()
    crop = img.crop(bbox)
    aspect = crop.width / crop.height
    small = crop.resize((W, H), Image.BOX)
    data = "".join("0123456789abcdef"[min(15, v // 16)] for v in small.tobytes())
    return aspect, data

def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "core/src/main/kotlin/com/tejerfacil/core/ocr/DigitTemplates.kt")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    lines = []
    for fp in FONTS:
        if not os.path.exists(fp):
            continue
        name = os.path.basename(fp)
        for ch in "0123456789":
            aspect, data = render(fp, ch)
            lines.append(f'        T({ch}, {aspect:.3f}, "{data}"), // {name}')
    with open(out, "w") as f:
        f.write("// Archivo generado por tools/gen_digit_templates.py. No editar a mano.\n")
        f.write("package com.tejerfacil.core.ocr\n\n")
        f.write("internal object DigitTemplates {\n")
        f.write(f"    const val W = {W}\n    const val H = {H}\n\n")
        f.write("    class T(val digit: Int, val aspect: Double, val data: String)\n\n")
        f.write("    val all: List<T> = listOf(\n")
        f.write("\n".join(lines))
        f.write("\n    )\n}\n")
    print("plantillas:", len(lines), "->", out)

if __name__ == "__main__":
    main()
