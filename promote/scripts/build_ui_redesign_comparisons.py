from math import cos, pi, sin
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


SCALE = 2
WIDTH, HEIGHT = 1086, 1448
CANVAS = (WIDTH * SCALE, HEIGHT * SCALE)
ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "promote/image"
LOGO_PATH = ROOT / "design/folio-logo.png"
HERO_PATH = ROOT / "design/backgroud.jpeg"
SMALL_LOGO_PATH = (
    ROOT / "design/code-mockups-travel/assets/system/"
    "folio-logo-stack-bold-small-50kb.png"
)
WORK_MEDIA_PATHS = (
    ROOT / "test/demo/demo-image-1.jpg",
    ROOT / "test/demo/demo-image-3-thumb.jpg",
    ROOT / "test/demo/demo-image-4-thumb.jpg",
    ROOT / "test/demo/demo-image-5-thumb.jpg",
    ROOT / "test/demo/demo-video-1-thumb.jpg",
)
FONT_MEDIUM = "/System/Library/Fonts/STHeiti Medium.ttc"
FONT_LIGHT = "/System/Library/Fonts/STHeiti Light.ttc"

INK_OLD = "#17202A"
MUTED_OLD = "#66727F"
FAINT_OLD = "#8B96A3"
LINE_OLD = "#D8E0E8"
BG_OLD = "#F5F7FB"
BLUE_OLD = "#3D6FAD"
GOLD_OLD = "#B88A44"
TEAL_OLD = "#0F766E"

INK_NEW = "#212529"
MUTED_NEW = "#868E96"
FAINT_NEW = "#ADB5BD"
LINE_NEW = "#E9ECEF"
BG_NEW = "#F5F6F7"
GREEN_NEW = "#5C9E6E"
AMBER_NEW = "#C08A3E"
RED_NEW = "#B55656"


def s(value):
    if isinstance(value, (tuple, list)):
        return tuple(round(v * SCALE) for v in value)
    return round(value * SCALE)


def font(size, light=False):
    return ImageFont.truetype(FONT_LIGHT if light else FONT_MEDIUM, s(size))


def rounded(draw, box, radius, fill, outline=None, width=1):
    x, y, w, h = box
    draw.rounded_rectangle(
        s((x, y, x + w, y + h)),
        radius=s(radius),
        fill=fill,
        outline=outline,
        width=s(width),
    )


def line(draw, points, fill, width=1):
    draw.line([s(p) for p in points], fill=fill, width=s(width), joint="curve")


def ellipse(draw, box, fill=None, outline=None, width=1):
    draw.ellipse(s(box), fill=fill, outline=outline, width=s(width))


def text(draw, xy, value, size, fill, light=False, anchor=None):
    draw.text(s(xy), value, font=font(size, light), fill=fill, anchor=anchor)


def text_center(draw, y, value, size, fill, light=False):
    text(draw, (WIDTH / 2, y), value, size, fill, light, "ma")


def rounded_mask(size, radius):
    mask = Image.new("L", s(size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, s(size[0]), s(size[1])),
        radius=s(radius),
        fill=255,
    )
    return mask


def paste_rounded(base, image, box, radius, mode="cover"):
    x, y, w, h = box
    source = image.convert("RGBA")
    if mode == "cover":
        fitted = ImageOps.fit(source, s((w, h)), Image.Resampling.LANCZOS)
    else:
        fitted = ImageOps.contain(source, s((w, h)), Image.Resampling.LANCZOS)
    base.paste(fitted, s((x, y)), rounded_mask((w, h), radius))


def paste_contain(base, image_path, box):
    x, y, w, h = box
    image = Image.open(image_path).convert("RGBA")
    fitted = ImageOps.contain(image, s((w, h)), Image.Resampling.LANCZOS)
    px = s(x) + (s(w) - fitted.width) // 2
    py = s(y) + (s(h) - fitted.height) // 2
    base.alpha_composite(fitted, (px, py))


def make_logo(width):
    logo = Image.open(LOGO_PATH).convert("RGBA").crop((110, 290, 1170, 920))
    alpha = Image.new("L", logo.size, 0)
    source = logo.convert("RGB")
    ap = alpha.load()
    sp = source.load()
    for yy in range(source.height):
        for xx in range(source.width):
            red, green, blue = sp[xx, yy]
            distance = max(255 - red, 255 - green, 255 - blue)
            ap[xx, yy] = max(0, min(255, (distance - 5) * 13))
    logo.putalpha(alpha)
    target_width = s(width)
    target_height = round(logo.height * target_width / logo.width)
    return logo.resize((target_width, target_height), Image.Resampling.LANCZOS)


def comparison_canvas(title_value, subtitle):
    base = Image.new("RGBA", CANVAS, "#F1F3F5")
    draw = ImageDraw.Draw(base)
    rounded(draw, (-170, 18, 305, 360), 92, "#F5F6F7", LINE_NEW, 1)
    rounded(draw, (880, 70, 330, 340), 76, "#F5F6F7", LINE_NEW, 1)
    rounded(draw, (-120, 1220, 330, 330), 82, "#E9ECEF")
    rounded(draw, (910, 1190, 310, 350), 82, "#F5F6F7", LINE_NEW, 1)
    logo = make_logo(142)
    base.alpha_composite(logo, (s(WIDTH / 2) - logo.width // 2, s(26)))
    rounded(draw, (414, 132, 258, 50), 25, "#FFFFFF", LINE_NEW, 1)
    text(draw, (543, 157), "BEFORE / AFTER", 20, INK_NEW, False, "mm")
    text_center(draw, 218, title_value, 52, INK_NEW)
    text_center(draw, 292, subtitle, 24, MUTED_NEW, True)
    rounded(draw, (139, 340, 320, 48), 24, "#FFFFFF", LINE_NEW, 1)
    rounded(draw, (627, 340, 320, 48), 24, INK_NEW)
    text(draw, (299, 364), "旧版 · BEFORE", 19, MUTED_OLD, False, "mm")
    text(draw, (787, 364), "新版 · AFTER", 19, "#FFFFFF", False, "mm")
    text_center(draw, 1384, "映期Folio• 小程序UI重设计", 20, FAINT_NEW, True)
    return base, draw


def finish(base, filename):
    output = base.convert("RGB").resize((WIDTH, HEIGHT), Image.Resampling.LANCZOS)
    path = OUT_DIR / filename
    output.save(path, "PNG", optimize=True)
    return path


def phone_shell(base, box, title_value, theme):
    x, y, w, h = box
    draw = ImageDraw.Draw(base)
    is_new = theme == "new"
    background = BG_NEW if is_new else BG_OLD
    ink = INK_NEW if is_new else INK_OLD
    radius = 38 if is_new else 27
    rounded(draw, box, radius, background, "#DDE2E6" if is_new else "#D4DDE4", 1)
    text(draw, (x + 35, y + 33), "9:41", 15, ink)
    signal_x = x + w - 88
    for index, height in enumerate((5, 8, 11, 14)):
        rounded(draw, (signal_x + index * 7, y + 43 - height, 4, height), 2, ink)
    text(draw, (x + w - 52, y + 34), "5G", 11, ink)
    rounded(draw, (x + w - 29, y + 33, 20, 11), 3, None, ink, 1)
    rounded(draw, (x + w - 26, y + 36, 12, 5), 2, ink)
    text(draw, (x + w / 2, y + 84), title_value, 21, ink, False, "ma")


def draw_search_icon(draw, cx, cy, color, size=10):
    ellipse(draw, (cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2), None, color, 1.5)
    line(draw, [(cx + size * 0.34, cy + size * 0.34), (cx + size * 0.78, cy + size * 0.78)], color, 1.5)


def draw_tab_icon(draw, kind, cx, cy, size, color):
    scale = size / 64

    def point(px, py):
        return cx + (px - 32) * scale, cy + (py - 32) * scale

    stroke = max(1.3, 4 * scale)
    if kind == "schedule":
        p1, p2 = point(14, 13), point(50, 52)
        rounded(draw, (p1[0], p1[1], p2[0] - p1[0], p2[1] - p1[1]), 8 * scale, None, color, stroke)
        line(draw, [point(23, 9), point(23, 18)], color, stroke)
        line(draw, [point(41, 9), point(41, 18)], color, stroke)
        line(draw, [point(16, 25), point(48, 25)], color, stroke)
        line(draw, [point(24, 36), point(40, 36)], color, stroke)
        line(draw, [point(24, 44), point(34, 44)], color, stroke)
    elif kind == "work":
        p1, p2 = point(12, 15), point(45, 49)
        rounded(draw, (p1[0], p1[1], p2[0] - p1[0], p2[1] - p1[1]), 8 * scale, None, color, stroke)
        line(draw, [point(17, 43), point(26, 34), point(33, 41), point(44, 42)], color, stroke)
        ellipse(draw, (*point(31, 21), *point(39, 29)), None, color, stroke)
    elif kind == "portfolio":
        p1, p2 = point(14, 12), point(50, 52)
        rounded(draw, (p1[0], p1[1], p2[0] - p1[0], p2[1] - p1[1]), 8 * scale, None, color, stroke)
        line(draw, [point(24, 12), point(24, 52)], color, stroke)
        line(draw, [point(31, 25), point(42, 25)], color, stroke)
        line(draw, [point(31, 34), point(42, 34)], color, stroke)
    else:
        ellipse(draw, (*point(22, 14), *point(42, 34)), None, color, stroke)
        points = []
        for index in range(31):
            angle = pi + pi * index / 30
            points.append((cx + cos(angle) * 16 * scale, cy + 18 * scale + sin(angle) * 14 * scale))
        line(draw, points, color, stroke)
        line(draw, [point(20, 52), point(44, 52)], color, stroke)


def draw_tabbar(base, box, active, theme):
    x, y, w, h = box
    draw = ImageDraw.Draw(base)
    items = (("schedule", "档期"), ("work", "作品"), ("portfolio", "作品集"), ("mine", "我的"))
    if theme == "new":
        rounded(draw, box, h / 2, "#FFFFFF", LINE_NEW, 1)
        for index, (kind, label) in enumerate(items):
            cx = x + w * (index + 0.5) / 4
            icon_y = y + 24
            if index == active:
                rounded(draw, (cx - 20, y + 9, 40, 30), 15, INK_NEW)
                draw_tab_icon(draw, kind, cx, icon_y, 23, "#FFFFFF")
                label_color = INK_NEW
            else:
                draw_tab_icon(draw, kind, cx, icon_y, 23, MUTED_NEW)
                label_color = MUTED_NEW
            text(draw, (cx, y + h - 17), label, 9, label_color, True, "ms")
    else:
        line(draw, [(x, y), (x + w, y)], "#E4E9EE", 1)
        rounded(draw, box, 0, "#FFFFFF")
        for index, (kind, label) in enumerate(items):
            cx = x + w * (index + 0.5) / 4
            color = GOLD_OLD if index == active else FAINT_OLD
            if index == active:
                rounded(draw, (cx - 12, y + 4, 24, 3), 2, GOLD_OLD)
            draw_tab_icon(draw, kind, cx, y + 26, 23, color)
            text(draw, (cx, y + h - 12), label, 9, color, True, "ms")


def draw_underlined_tabs(draw, box, active, theme):
    x, y, w, h = box
    ink = INK_NEW if theme == "new" else INK_OLD
    muted = MUTED_NEW if theme == "new" else MUTED_OLD
    accent = INK_NEW if theme == "new" else BLUE_OLD
    line_color = LINE_NEW if theme == "new" else LINE_OLD
    line(draw, [(x, y + h), (x + w, y + h)], line_color, 1)
    for index, label in enumerate(("体验", "登录 / 注册")):
        cx = x + w * (index + 0.5) / 2
        text(draw, (cx, y + h / 2), label, 15 if theme == "new" else 14, ink if index == active else muted, False, "mm")
        if index == active:
            line(draw, [(cx - 22, y + h - 1), (cx + 22, y + h - 1)], accent, 3)


def draw_login_phone(base, box, theme):
    x, y, w, h = box
    phone_shell(base, box, "映期Folio", theme)
    draw = ImageDraw.Draw(base)
    is_new = theme == "new"
    ink = INK_NEW if is_new else INK_OLD
    muted = MUTED_NEW if is_new else MUTED_OLD
    line_color = LINE_NEW if is_new else LINE_OLD
    card_radius = 24 if is_new else 8
    hero_radius = 24 if is_new else 8
    hero = (x + 17, y + 116, w - 34, 350)
    hero_image = ImageOps.fit(
        Image.open(HERO_PATH).convert("RGBA"),
        s((hero[2], hero[3])),
        Image.Resampling.LANCZOS,
    )
    overlay = Image.new("RGBA", hero_image.size, (0, 0, 0, 0))
    overlay_draw = ImageDraw.Draw(overlay)
    for yy in range(hero_image.height):
        ratio = max(0, yy / hero_image.height - 0.38) / 0.62
        overlay_draw.line((0, yy, hero_image.width, yy), fill=(23, 32, 42, round(168 * ratio)))
    hero_image.alpha_composite(overlay)
    base.paste(hero_image, s((hero[0], hero[1])), rounded_mask((hero[2], hero[3]), hero_radius))
    draw = ImageDraw.Draw(base)
    text(draw, (x + 36, y + 434), "把作品和档期，装进一张可分享名片", 16, "#FFFFFF")
    rounded(draw, (x + 17, y + 478, w - 34, 106), card_radius, "#FFFFFF", line_color, 1)
    text(draw, (x + 36, y + 500), "欢迎使用映期Folio", 18, ink)
    text(draw, (x + 36, y + 534), "适合分享你的照片与视频，并自由制作", 11, muted, True)
    text(draw, (x + 36, y + 553), "属于你的可分享名片。", 11, muted, True)
    paste_contain(base, SMALL_LOGO_PATH, (x + w - 78, y + 506, 42, 42))
    draw = ImageDraw.Draw(base)
    rounded(draw, (x + 17, y + 596, w - 34, 256), card_radius, "#FFFFFF", line_color, 1)
    draw_underlined_tabs(draw, (x + 38, y + 617, w - 76, 44), 1, theme)
    text(draw, (x + 39, y + 688), "推荐码", 13, ink)
    text(draw, (x + w - 39, y + 688), "0/16", 11, FAINT_NEW if is_new else FAINT_OLD, True, "ra")
    if is_new:
        rounded(draw, (x + 38, y + 712, w - 76, 44), 22, BG_NEW)
        button_radius = 24
        button_fill = INK_NEW
    else:
        rounded(draw, (x + 38, y + 712, w - 76, 44), 8, "#F8FAFB", "#E4EBEF", 1)
        button_radius = 8
        button_fill = INK_OLD
    text(draw, (x + 56, y + 734), "填写他人的个人唯一码", 12, FAINT_NEW if is_new else "#9AA5AF", True, "lm")
    text(draw, (x + w - 56, y + 734), "选填", 11, muted, True, "rm")
    text(draw, (x + 39, y + 780), "推荐码仅新用户注册时有效", 11, muted, True)
    rounded(draw, (x + 38, y + 803, w - 76, 48), button_radius, button_fill)
    text(draw, (x + w / 2, y + 827), "手机号快捷注册", 15, "#FFFFFF", False, "mm")


def draw_schedule_tabs(draw, box, theme):
    x, y, w, h = box
    if theme == "new":
        line(draw, [(x, y + h), (x + w, y + h)], LINE_NEW, 1)
        for index, label in enumerate(("档期维护", "档位定义")):
            cx = x + w * (index + 0.5) / 2
            text(draw, (cx, y + h / 2), label, 13, INK_NEW if index == 0 else MUTED_NEW, index != 0, "mm")
        line(draw, [(x + w / 4 - 18, y + h - 1), (x + w / 4 + 18, y + h - 1)], INK_NEW, 3)
    else:
        rounded(draw, box, 9, "#FFFFFF", LINE_OLD, 1)
        rounded(draw, (x + 4, y + 4, w / 2 - 6, h - 8), 7, INK_OLD)
        text(draw, (x + w / 4, y + h / 2), "档期维护", 13, "#FFFFFF", False, "mm")
        text(draw, (x + w * 0.75, y + h / 2), "档位定义", 13, MUTED_OLD, False, "mm")


def calendar_days():
    return [29, 30] + list(range(1, 32)) + [1, 2]


def draw_calendar(draw, box, theme):
    x, y, w, h = box
    is_new = theme == "new"
    ink = INK_NEW if is_new else INK_OLD
    muted = FAINT_NEW if is_new else MUTED_OLD
    line_color = LINE_NEW if is_new else LINE_OLD
    radius = 24 if is_new else 9
    rounded(draw, box, radius, "#FFFFFF", None if is_new else line_color, 1)
    text(draw, (x + 22, y + 27), "2026 年 7 月", 17, ink, False, "lm")
    rounded(draw, (x + w - 105, y + 12, 88, 32), 16, "#FFFFFF", line_color, 1)
    text(draw, (x + w - 61, y + 28), "切换年月", 11, ink, False, "mm")
    weekdays = ("日", "一", "二", "三", "四", "五", "六")
    col_width = (w - 28) / 7
    for index, label in enumerate(weekdays):
        text(draw, (x + 14 + col_width * (index + 0.5), y + 62), label, 10 if is_new else 11, muted, False, "mm")
    days = calendar_days()
    cell_height = 42 if is_new else 44
    start_y = y + 81
    for index, day in enumerate(days):
        row = index // 7
        col = index % 7
        cx = x + 14 + col_width * (col + 0.5)
        cy = start_y + row * cell_height
        outside = index < 2 or index >= 33
        selected = day == 25 and not outside
        if selected:
            ellipse(draw, (cx - 17, cy - 17, cx + 17, cy + 17), fill=INK_NEW if is_new else BLUE_OLD)
        day_color = "#FFFFFF" if selected else ("#CED4DA" if is_new and outside else "#B6C0CA" if outside else ink)
        text(draw, (cx, cy - 2), str(day), 13 if is_new else 14, day_color, False, "mm")
        if not outside and day in (5, 14, 25):
            dot_color = GREEN_NEW if is_new and day == 5 else AMBER_NEW if is_new and day == 25 else "#B87F8D" if is_new else "#A9354F"
            ellipse(draw, (cx - 3, cy + 14, cx + 3, cy + 20), fill=dot_color)


def draw_schedule_phone(base, box, theme):
    x, y, w, h = box
    phone_shell(base, box, "档期", theme)
    draw = ImageDraw.Draw(base)
    is_new = theme == "new"
    ink = INK_NEW if is_new else INK_OLD
    muted = MUTED_NEW if is_new else MUTED_OLD
    line_color = LINE_NEW if is_new else LINE_OLD
    draw_schedule_tabs(draw, (x + 17, y + 108, w - 34, 44), theme)
    card_radius = 24 if is_new else 8
    rounded(draw, (x + 17, y + 164, w - 34, 87), card_radius, "#FFFFFF", None if is_new else line_color, 1)
    text(draw, (x + 38, y + 187), "档位定义", 15, ink)
    text(draw, (x + 38, y + 217), "4 个档位 · 颜色用于月历标记", 11, muted, True)
    colors = (GREEN_NEW, AMBER_NEW, RED_NEW) if is_new else ("#2E7D73", "#D9901F", "#A9354F")
    labels = ("上午", "下午", "全天")
    for index, (color, label) in enumerate(zip(colors, labels)):
        px = x + 228 + index * 61
        if is_new:
            ellipse(draw, (px, y + 197, px + 7, y + 204), fill=color)
            text(draw, (px + 12, y + 200), label, 10, color, True, "lm")
        else:
            rounded(draw, (px, y + 187, 56, 27), 14, "#F5F8FB")
            ellipse(draw, (px + 8, y + 196, px + 17, y + 205), fill=color)
            text(draw, (px + 22, y + 200), label, 9, MUTED_OLD, True, "lm")
    draw_calendar(draw, (x + 17, y + 263, w - 34, 342), theme)
    rounded(draw, (x + 17, y + 617, w - 34, 190), card_radius, "#FFFFFF", None if is_new else line_color, 1)
    text(draw, (x + 38, y + 644), "7月25日", 18, ink)
    text(draw, (x + 127, y + 646), "农历六月十二", 11, muted, True)
    text(draw, (x + w - 38, y + 646), "2 条档期", 11, ink if is_new else muted, False, "ra")
    entries = (
        ("上午档", "09:00–12:00", colors[0], "可约", GREEN_NEW if is_new else TEAL_OLD),
        ("晚宴档", "17:00–21:00", colors[1], "休息", MUTED_NEW if is_new else MUTED_OLD),
    )
    for index, (name, time_value, color, status, status_color) in enumerate(entries):
        row_y = y + 678 + index * 48
        if index:
            line(draw, [(x + 38, row_y - 8), (x + w - 38, row_y - 8)], "#F1F3F5" if is_new else "#EDF1F5", 1)
        ellipse(draw, (x + 39, row_y - 4, x + 47, row_y + 4), fill=color)
        text(draw, (x + 58, row_y - 8), name, 13, ink)
        text(draw, (x + 58, row_y + 13), time_value, 10, muted, True)
        if is_new:
            ellipse(draw, (x + w - 73, row_y - 1, x + w - 67, row_y + 5), fill=status_color)
            text(draw, (x + w - 39, row_y + 2), status, 10, status_color, True, "rm")
        else:
            rounded(draw, (x + w - 88, row_y - 10, 50, 25), 13, "#E7F8F4" if index == 0 else "#F1F3F5")
            text(draw, (x + w - 63, row_y + 2), status, 10, status_color, False, "mm")
    if is_new:
        rounded(draw, (x + 38, y + 760, w - 76, 42), 21, INK_NEW)
    else:
        rounded(draw, (x + 38, y + 760, w - 76, 42), 8, INK_OLD)
    text(draw, (x + w / 2, y + 781), "新增档期", 14, "#FFFFFF", False, "mm")
    if is_new:
        draw_tabbar(base, (x + 17, y + h - 84, w - 34, 68), 0, "new")
    else:
        draw_tabbar(base, (x, y + h - 70, w, 70), 0, "old")


def work_thumbnail(base, box, index, theme):
    x, y, w, h = box
    radius = 20 if theme == "new" else 6
    paste_rounded(base, Image.open(WORK_MEDIA_PATHS[index]), box, radius, "cover")
    if index == 4:
        draw = ImageDraw.Draw(base)
        ellipse(draw, (x + w / 2 - 14, y + h / 2 - 14, x + w / 2 + 14, y + h / 2 + 14), fill="#FFFFFF")
        triangle = [
            (x + w / 2 - 4, y + h / 2 - 7),
            (x + w / 2 + 8, y + h / 2),
            (x + w / 2 - 4, y + h / 2 + 7),
        ]
        draw.polygon([s(p) for p in triangle], fill=INK_NEW if theme == "new" else INK_OLD)


def draw_works_phone(base, box, theme):
    x, y, w, h = box
    phone_shell(base, box, "作品", theme)
    draw = ImageDraw.Draw(base)
    is_new = theme == "new"
    ink = INK_NEW if is_new else INK_OLD
    muted = MUTED_NEW if is_new else MUTED_OLD
    line_color = LINE_NEW if is_new else LINE_OLD
    card_radius = 24 if is_new else 8
    search_y = y + 112
    if is_new:
        rounded(draw, (x + 17, search_y, w - 34, 44), 22, BG_NEW)
    else:
        rounded(draw, (x + 17, search_y, w - 34, 44), 8, "#FFFFFF", line_color, 1)
    draw_search_icon(draw, x + 42, search_y + 22, FAINT_NEW if is_new else MUTED_OLD, 10)
    text(draw, (x + 60, search_y + 22), "搜索作品标题、标签", 13, FAINT_NEW if is_new else MUTED_OLD, True, "lm")
    filter_y = y + 168
    tags = (("全部 7", 82), ("风景作品 7", 84), ("视频 1", 62))
    cursor = x + 17
    for index, (label, tag_width) in enumerate(tags):
        if is_new and index == 0:
            rounded(draw, (cursor, filter_y, tag_width, 30), 15, INK_NEW)
            label_color = "#FFFFFF"
        elif is_new:
            rounded(draw, (cursor, filter_y, tag_width, 30), 15, "#FFFFFF", LINE_NEW, 1)
            label_color = MUTED_NEW
        elif index == 0:
            rounded(draw, (cursor, filter_y, tag_width, 28), 14, "#EEF4F7", "#CBD8E5", 1)
            label_color = "#40546A"
        else:
            rounded(draw, (cursor, filter_y, tag_width, 28), 14, "#FFFFFF", "#D4DEE8", 1)
            label_color = INK_OLD
        text(draw, (cursor + tag_width / 2, filter_y + 15), label, 11, label_color, False, "mm")
        cursor += tag_width + 7
    rounded(draw, (x + w - 50, filter_y - 1, 32, 32), 16, "#FFFFFF", line_color, 1)
    text(draw, (x + w - 34, filter_y + 15), "+", 17, ink, True, "mm")
    panel_y = y + 212
    rounded(draw, (x + 17, panel_y, w - 34, 586), card_radius, "#FFFFFF", line_color, 1)
    text(draw, (x + 38, panel_y + 28), "素材库", 18, ink)
    if is_new:
        rounded(draw, (x + w - 115, panel_y + 12, 77, 30), 15, "#FFFFFF", LINE_NEW, 1)
    else:
        rounded(draw, (x + w - 115, panel_y + 12, 77, 30), 15, "#F2F7FF", "#BDD6FF", 1)
    text(draw, (x + w - 76, panel_y + 27), "批量管理", 11, ink if is_new else "#2F66AA", False, "mm")
    works = (
        ("风景图片 1", "图片 / 风景作品 / 长宽比 4:3", "引用 1 次", "审核通过"),
        ("风景图片 3", "图片 / 风景作品 / 长宽比 4:3", "引用 1 次", "审核通过"),
        ("风景图片 4", "图片 / 风景作品 / 长宽比 4:3", "未引用", "审核通过"),
        ("风景图片 5", "图片 / 风景作品 / 长宽比 4:3", "未引用", "审核通过"),
        ("风景视频 1", "视频 · 00:15 / 风景作品 / 长宽比 16:9", "引用 1 次", "审核通过"),
    )
    card_height = 86
    for index, (title_value, meta, reference, status) in enumerate(works):
        row_y = panel_y + 58 + index * 94
        rounded(draw, (x + 32, row_y, w - 64, card_height), card_radius if is_new else 8, "#FFFFFF", line_color, 1)
        work_thumbnail(base, (x + 44, row_y + 12, 64, 62), index, theme)
        draw = ImageDraw.Draw(base)
        text(draw, (x + 120, row_y + 11), title_value, 13, ink)
        text(draw, (x + 120, row_y + 35), meta, 9, muted, True)
        rounded(draw, (x + 120, row_y + 56, 58, 22), 11, "#FFFFFF", line_color, 1)
        text(draw, (x + 149, row_y + 67), reference, 9, ink if reference != "未引用" else muted, True, "mm")
        if is_new:
            color = GREEN_NEW if status == "审核通过" else MUTED_NEW
            ellipse(draw, (x + 187, row_y + 64, x + 193, row_y + 70), fill=color)
            text(draw, (x + 200, row_y + 67), status, 9, color, True, "lm")
        else:
            fill_color = "#EDF9F5" if status == "审核通过" else "#F8FAFC"
            outline = "#B9E6DC" if status == "审核通过" else LINE_OLD
            label_color = TEAL_OLD if status == "审核通过" else MUTED_OLD
            rounded(draw, (x + 187, row_y + 56, 72, 22), 11, fill_color, outline, 1)
            text(draw, (x + 223, row_y + 67), status, 9, label_color, False, "mm")
        text(draw, (x + w - 49, row_y + card_height / 2), "›", 23, FAINT_NEW if is_new else FAINT_OLD, True, "mm")
    if is_new:
        draw_tabbar(base, (x + 17, y + h - 84, w - 34, 68), 1, "new")
    else:
        draw_tabbar(base, (x, y + h - 70, w, 70), 1, "old")


def build_login_comparison():
    base, _ = comparison_canvas("登录 / 注册 UI 对比", "同一内容 · 两套视觉语言")
    draw_login_phone(base, (76, 408, 448, 900), "old")
    draw_login_phone(base, (562, 408, 448, 900), "new")
    return finish(base, "ui-redesign-compare-login-v1.png")


def build_schedule_comparison():
    base, _ = comparison_canvas("档期页面 UI 对比", "月历 · 档期卡片 · 底部导航")
    draw_schedule_phone(base, (76, 408, 448, 900), "old")
    draw_schedule_phone(base, (562, 408, 448, 900), "new")
    return finish(base, "ui-redesign-compare-schedule-v1.png")


def build_works_comparison():
    base, _ = comparison_canvas("作品列表 UI 对比", "作品类型 · 长宽比 · 引用状态")
    draw_works_phone(base, (76, 408, 448, 900), "old")
    draw_works_phone(base, (562, 408, 448, 900), "new")
    return finish(base, "ui-redesign-compare-works-v1.png")


def main():
    paths = [
        build_login_comparison(),
        build_schedule_comparison(),
        build_works_comparison(),
    ]
    for path in paths:
        print(path.resolve())
    return paths


if __name__ == "__main__":
    main()
