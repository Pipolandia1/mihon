"""
Synthetic comics for checking the Início tab (docs/HOME_PLAN.md) on the emulator.

Every page is drawn here, with made-up titles, credits and characters: nothing real goes into the
project or onto the device. Each series becomes a folder of CBZ files, each with a ComicInfo.xml,
pushed to Mihon's local source:

    <storage>/local/Teste - <Name>/Teste - <Name> #<n>.cbz

The mix covers the H2 blocks: short finished series (known totals, "Histórias curtas"), one of 8
issues to mark mostly read ("Quase terminando"), ongoing series never started ("Nunca começou",
more than a shelf holds so the daily rotation shows), one to read in the reader so a pace is
measured, and one to read to the end and then give a new issue ("Continuar lendo" leaves and
comes back): run again with --new-issue to push that issue.

Usage:
    python tools/make-start-test-library.py            # generate and push everything
    python tools/make-start-test-library.py --new-issue
"""

import argparse
import io
import os
import subprocess
import tempfile
import zipfile

from PIL import Image, ImageDraw, ImageFont

STORAGE = "/storage/emulated/0/Mihon/local"
PREFIX = "Teste - "
PAGES = 3
SIZE = (800, 1200)

# name, issues, finished, writer, artist, characters, colour
SERIES = [
    ("Lanterna Noturna", 4, True, "Ana Torres", "Rui Campos", "Lanterna; Sombra", (40, 60, 120)),
    ("A Ultima Estacao", 3, True, "Bruno Lima", "Ines Rocha", "Maquinista", (90, 50, 40)),
    ("Mare Vermelha", 6, True, "Ana Torres", "Davi Prado", "Capita Rubra", (140, 30, 40)),
    ("Filha do Amanha", 8, True, "Carla Nunes", "Rui Campos", "Filha do Amanha; Cometa", (30, 110, 120)),
    ("Cidade Cinza", 12, False, "Bruno Lima", "Davi Prado", "Detetive Cinza", (90, 90, 90)),
    ("Guardioes do Norte", 15, False, "Carla Nunes", "Ines Rocha", "Guardiao; Aurora", (40, 100, 60)),
    ("Eco", 10, False, "Ana Torres", "Ines Rocha", "Eco", (100, 60, 130)),
    ("Arquivo Morto", 9, False, "Bruno Lima", "Rui Campos", "Arquivista", (60, 60, 30)),
    ("Os Sete Portoes", 14, False, "Carla Nunes", "Davi Prado", "Porteira", (120, 90, 30)),
    ("Ferrugem", 11, False, "Ana Torres", "Davi Prado", "Ferrugem", (150, 80, 30)),
    ("Ceu de Chumbo", 13, False, "Bruno Lima", "Ines Rocha", "Piloto", (70, 80, 100)),
    ("Vigilia", 10, False, "Carla Nunes", "Rui Campos", "Vigia", (30, 30, 60)),
    ("Linha de Fuga", 12, False, "Ana Torres", "Rui Campos", "Fugitiva", (120, 40, 90)),
    ("Ossos do Oficio", 9, False, "Bruno Lima", "Davi Prado", "Coveiro", (80, 70, 60)),
    ("Coracao de Vidro", 5, False, "Carla Nunes", "Ines Rocha", "Vidreira", (50, 130, 150)),
    ("Rastro", 3, False, "Ana Torres", "Davi Prado", "Rastreadora", (110, 110, 40)),
]
NEW_ISSUE_SERIES = "Rastro"


def comic_info(name, number, count, finished, writer, artist, characters):
    count_tag = f"<Count>{count}</Count>" if finished else ""
    status = "Completed" if finished else "Ongoing"
    return f"""<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:ty="http://www.w3.org/2001/XMLSchema">
  <Title>{name} #{number}</Title>
  <Series>{PREFIX}{name}</Series>
  <Number>{number}</Number>
  {count_tag}
  <Year>2025</Year>
  <Writer>{writer}</Writer>
  <Penciller>{artist}</Penciller>
  <Publisher>Editora Teste</Publisher>
  <Genre>Teste</Genre>
  <Characters>{characters.replace(';', ',')}</Characters>
  <Summary>HQ sintetica para testar o Inicio. Paginas desenhadas pelo script.</Summary>
  <ty:PublishingStatusTachiyomi>{status}</ty:PublishingStatusTachiyomi>
</ComicInfo>
"""


def page(name, number, index, colour):
    image = Image.new("RGB", SIZE, colour)
    draw = ImageDraw.Draw(image)
    try:
        big = ImageFont.truetype("arialbd.ttf", 72)
        small = ImageFont.truetype("arialbd.ttf", 44)
    except OSError:
        big = small = ImageFont.load_default()
    draw.rectangle([40, 40, SIZE[0] - 40, SIZE[1] - 40], outline=(255, 255, 255), width=6)
    draw.text((80, 420), name, fill=(255, 255, 255), font=big)
    draw.text((80, 540), f"#{number}", fill=(255, 255, 255), font=big)
    draw.text((80, 660), f"pagina {index} de {PAGES}", fill=(230, 230, 230), font=small)
    out = io.BytesIO()
    image.save(out, "PNG", optimize=True)
    return out.getvalue()


def write_issue(folder, series, number):
    name, count, finished, writer, artist, characters, colour = series
    path = os.path.join(folder, f"{PREFIX}{name} #{number}.cbz")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as cbz:
        cbz.writestr("ComicInfo.xml", comic_info(name, number, count, finished, writer, artist, characters))
        for index in range(1, PAGES + 1):
            cbz.writestr(f"{index:03d}.png", page(name, number, index, colour))


def push(local_folder, name):
    target = f"{STORAGE}/{PREFIX}{name}"
    subprocess.run(["adb", "shell", "mkdir", "-p", f"'{target}'"], check=True)
    for file in sorted(os.listdir(local_folder)):
        subprocess.run(["adb", "push", os.path.join(local_folder, file), f"{target}/{file}"],
                       check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--new-issue", action="store_true",
                        help=f"push only the next issue of {NEW_ISSUE_SERIES}, after its first ones were read")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        for series in SERIES:
            name, count = series[0], series[1]
            if args.new_issue and name != NEW_ISSUE_SERIES:
                continue
            folder = os.path.join(tmp, name)
            os.makedirs(folder)
            numbers = [count + 1] if args.new_issue else range(1, count + 1)
            for number in numbers:
                write_issue(folder, series, number)
            push(folder, name)
            print(f"{name}: {len(list(numbers))} issue(s)")


if __name__ == "__main__":
    main()
