"""Generate Play Store feature graphic (1024x500) for DroidProbe."""
from PIL import Image, ImageDraw, ImageFont
import math

WIDTH, HEIGHT = 1024, 500
BG = (13, 17, 23)        # #0D1117
GREEN = (63, 185, 80)    # #3FB950
DIM_GREEN = (63, 185, 80, 40)
WHITE = (255, 255, 255)
GRAY = (139, 148, 158)

img = Image.new("RGBA", (WIDTH, HEIGHT), BG + (255,))
draw = ImageDraw.Draw(img)

# --- Subtle grid/circuit background pattern ---
for x in range(0, WIDTH, 40):
    draw.line([(x, 0), (x, HEIGHT)], fill=(30, 37, 46, 80), width=1)
for y in range(0, HEIGHT, 40):
    draw.line([(0, y), (WIDTH, y)], fill=(30, 37, 46, 80), width=1)

# Circuit-like dots at intersections (sparse)
for x in range(0, WIDTH, 80):
    for y in range(0, HEIGHT, 80):
        if (x + y) % 160 == 0:
            draw.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(63, 185, 80, 30))

# --- Shield icon (centered-left area) ---
cx, cy = 280, 250
s = 90  # shield half-width

# Shield shape as polygon
shield_points = [
    (cx, cy - s * 1.2),           # top center
    (cx + s, cy - s * 0.7),      # top right
    (cx + s, cy + s * 0.2),      # mid right
    (cx + s * 0.6, cy + s * 0.8),# lower right
    (cx, cy + s * 1.1),          # bottom center
    (cx - s * 0.6, cy + s * 0.8),# lower left
    (cx - s, cy + s * 0.2),      # mid left
    (cx - s, cy - s * 0.7),      # top left
]
draw.polygon(shield_points, fill=GREEN)

# Inner shield (dark)
inner_s = s * 0.75
inner_points = [
    (cx, cy - inner_s * 1.2),
    (cx + inner_s, cy - inner_s * 0.7),
    (cx + inner_s, cy + inner_s * 0.2),
    (cx + inner_s * 0.6, cy + inner_s * 0.8),
    (cx, cy + inner_s * 1.1),
    (cx - inner_s * 0.6, cy + inner_s * 0.8),
    (cx - inner_s, cy + inner_s * 0.2),
    (cx - inner_s, cy - inner_s * 0.7),
]
draw.polygon(inner_points, fill=BG)

# Probe/magnifying glass icon inside shield
# Circle
r = 28
gcx, gcy = cx - 8, cy - 10
draw.ellipse([gcx - r, gcy - r, gcx + r, gcy + r], outline=GREEN, width=4)
# Handle
hx, hy = gcx + int(r * 0.7), gcy + int(r * 0.7)
draw.line([(hx, hy), (hx + 25, hy + 25)], fill=GREEN, width=5)
# Crosshair inside circle
draw.line([(gcx - 14, gcy), (gcx + 14, gcy)], fill=GREEN, width=2)
draw.line([(gcx, gcy - 14), (gcx, gcy + 14)], fill=GREEN, width=2)

# --- Text ---
# Try to use a nice font, fall back to default
try:
    font_title = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 72)
    font_tagline = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 28)
    font_features = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 20)
except:
    font_title = ImageFont.load_default()
    font_tagline = ImageFont.load_default()
    font_features = ImageFont.load_default()

text_x = 440

# App name
draw.text((text_x, 140), "DroidProbe", fill=WHITE, font=font_title)

# Tagline
draw.text((text_x, 225), "Android Attack Surface Scanner", fill=GREEN, font=font_tagline)

# Feature bullets
features = [
    "Content Providers  ·  Intent Builder  ·  File Providers",
    "Retrofit & OkHttp Endpoint Detection",
    "Secret & API Key Discovery",
    "On-device  ·  No Root Required",
]
y_start = 290
for i, feat in enumerate(features):
    # Small green dot
    dot_y = y_start + i * 34 + 10
    draw.ellipse([text_x, dot_y - 3, text_x + 6, dot_y + 3], fill=GREEN)
    draw.text((text_x + 16, y_start + i * 34), feat, fill=GRAY, font=font_features)

# --- Subtle green glow behind shield ---
glow = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
glow_draw = ImageDraw.Draw(glow)
for radius in range(150, 20, -5):
    alpha = max(0, int(8 * (1 - radius / 150)))
    glow_draw.ellipse(
        [cx - radius, cy - radius, cx + radius, cy + radius],
        fill=(63, 185, 80, alpha)
    )

# Composite glow behind main image
final = Image.new("RGBA", (WIDTH, HEIGHT), BG + (255,))
final = Image.alpha_composite(final, glow)
final = Image.alpha_composite(final, img)

# Save as PNG
output_path = "d:/androidast/feature_graphic.png"
final.convert("RGB").save(output_path, "PNG")
print(f"Saved to {output_path}")
print(f"Size: {final.size}")
