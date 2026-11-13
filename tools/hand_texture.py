#!/usr/bin/env python3
"""
Paints the Sky Hand skin: cream surface covered in hand-written words (black, some red and blue),
following the concept board palette. Output: textures/entity/sky_hand.png (1024x1024).
The bottom-right corner (u, v > 0.94) is left as plain nail color, see hand_generator.NAIL_UV.
"""
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SIZE = 1024
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'assets', 'orbital_laser', 'textures', 'entity',
    'sky_hand.png')

CREAM = (234, 227, 212)
NAIL = (236, 214, 204)
INKS = [((28, 26, 30), 0.72), ((196, 58, 52), 0.16), ((52, 78, 160), 0.12)]

WORDS = [
    'why?', 'please', 'mom', 'help', 'hope', 'home', 'dream', 'stay', 'never', 'love', 'run', 'again',
    'remember', 'sky', 'fall', 'light', 'I was here', "don't forget", 'breathe', 'together', 'listen',
    'Ana', 'Leo', 'Maria', 'José', 'Alisson', 'Frank', 'Nate', 'Mike', 'Sam', 'Rosa', 'Bia', 'Theo',
    'fé', 'saudade', 'esperança', 'voa', 'céu', 'volta', 'sonho', 'calma', 'sempre', 'aqui', 'casa',
    'luz', 'medo', 'coragem', 'obrigado', 'fica', 'agora', 'amor', 'vida', 'tempo', 'lembra',
    'why me', 'ok', 'yes', 'no', 'wait', 'more', 'up', 'reach', 'grab', 'hold on', 'let go',
]
BIG_WORDS = ['WHY?', 'HELP', 'HOPE', 'please', 'SKY', 'ESPERANÇA', 'love', 'RUN']

FONT_FILES = [
    '/usr/share/fonts/truetype/dejavu/DejaVuSans-Oblique.ttf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSerif-Italic.ttf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans-BoldOblique.ttf',
    '/usr/share/fonts/truetype/crosextra/Carlito-Italic.ttf',
    '/usr/share/fonts/truetype/crosextra/Caladea-Italic.ttf',
    '/usr/share/fonts/truetype/freefont/FreeMonoOblique.ttf',
    '/usr/share/fonts/truetype/freefont/FreeSerif.ttf',
]
DEVANAGARI_FONT = '/usr/share/fonts/truetype/freefont/FreeSans.ttf'


def fonts():
    found = [f for f in FONT_FILES if os.path.exists(f)]
    return found or [None]


def load(path, size):
    if path is None:
        return ImageFont.load_default()
    return ImageFont.truetype(path, size)


def pick_ink(rnd):
    r = rnd.random()
    acc = 0
    for color, w in INKS:
        acc += w
        if r <= acc:
            return color
    return INKS[0][0]


def stamp(base, rnd, text, font, color, angle, center):
    bbox = font.getbbox(text)
    w = bbox[2] - bbox[0] + 8
    h = bbox[3] - bbox[1] + 8
    layer = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    alpha = rnd.randint(170, 245)
    d.text((4 - bbox[0], 4 - bbox[1]), text, font=font, fill=color + (alpha,))
    if rnd.random() < 0.25:   # a quick underline
        y = h - 4
        d.line([(4, y), (w - 4 - rnd.randint(0, w // 3), y + rnd.randint(-2, 2))], fill=color + (alpha,), width=2)
    layer = layer.rotate(angle, resample=Image.BICUBIC, expand=True)
    x = int(center[0] - layer.width / 2)
    y = int(center[1] - layer.height / 2)
    base.alpha_composite(layer, (x, y))


def main():
    rnd = random.Random(1945)
    img = Image.new('RGBA', (SIZE, SIZE), CREAM + (255,))

    # paper / plaster grain
    grain = Image.effect_noise((SIZE, SIZE), 18).filter(ImageFilter.GaussianBlur(0.8))
    grain_rgb = Image.merge('RGBA', (grain, grain, grain, Image.new("L", (SIZE, SIZE), 12)))
    img.alpha_composite(grain_rgb)

    fl = fonts()
    # small words everywhere, a few passes at different sizes
    for size, count in ((15, 480), (22, 240), (30, 80)):
        for _ in range(count):
            text = rnd.choice(WORDS)
            font = load(rnd.choice(fl), size + rnd.randint(-3, 4))
            angle = rnd.choice([0, 0, 0, 90, -90, 180]) + rnd.uniform(-35, 35)
            stamp(img, rnd, text, font, pick_ink(rnd), angle, (rnd.uniform(0, SIZE), rnd.uniform(0, SIZE)))
    # a few big statements
    for _ in range(9):
        text = rnd.choice(BIG_WORDS)
        font = load(rnd.choice(fl), rnd.randint(40, 64))
        angle = rnd.choice([0, 90, -90]) + rnd.uniform(-20, 20)
        stamp(img, rnd, text, font, pick_ink(rnd), angle, (rnd.uniform(0, SIZE), rnd.uniform(0, SIZE)))
    # the Devanagari word from the concept board, if the font is available
    if os.path.exists(DEVANAGARI_FONT):
        for _ in range(5):
            font = load(DEVANAGARI_FONT, rnd.randint(46, 80))
            stamp(img, rnd, 'आशा', font, (28, 26, 30), rnd.choice([0, 90, -90]) + rnd.uniform(-10, 10),
                  (rnd.uniform(0, SIZE), rnd.uniform(0, SIZE)))

    # nail corner: plain
    d = ImageDraw.Draw(img)
    n0 = int(SIZE * 0.94)
    d.rectangle([n0, n0, SIZE, SIZE], fill=NAIL + (255,))

    img = img.convert('RGB')
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.save(OUT, optimize=True)
    print('texture ->', OUT)


if __name__ == '__main__':
    main()
