#!/usr/bin/env python3
"""Generates the physical-heraldry resources (B1): textures in the Book's parchment, ink and walnut palette, and the
blockstates, models, loot tables, recipes, recipe-unlock advancements and tool tags of the kingdom banner, heraldic
shield, court noticeboard, seal press and kingdom charter. Tinted surfaces are drawn in light greys so the kingdom's
colours show true. Deterministic: the same script writes the same bytes. Run: python3 -I tools/art/heraldry_assets.py"""
import json, pathlib, random
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2] / 'src/main/resources'
ASSETS, DATA = ROOT / 'assets/mcakingdoms', ROOT / 'data'
WALNUT = [(58, 38, 24), (72, 47, 29), (86, 58, 36), (98, 67, 41)]
IRON = [(92, 92, 98), (122, 122, 128), (150, 150, 156), (176, 176, 182)]
WAX = [(120, 24, 28), (148, 34, 38), (176, 52, 54)]
PARCHMENT = [(214, 196, 158), (226, 210, 174), (236, 224, 192), (198, 178, 140)]
INK = (52, 36, 26)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def texture(name, draw, size=16):
    image = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw(image.load(), random.Random(name), size)
    path = ASSETS / 'textures' / f'{name}.png'
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def wood(px, rnd, size):
    for y in range(size):
        for x in range(size):
            grain = (x * 3 + (y // 4) * 5) % 7
            px[x, y] = WALNUT[min(3, (grain + rnd.randrange(2)) // 2)] + (255,)


def cloth(px, rnd, size):
    for y in range(size):
        for x in range(size):
            v = 226 + ((x + y) % 2) * 10 + rnd.randrange(8)
            px[x, y] = (v, v, v, 255)


def trim(px, rnd, size):
    for y in range(size):
        for x in range(size):
            v = 214 + (18 if (x % 4 == 0 or y % 4 == 0) else 0) + rnd.randrange(6)
            px[x, y] = (v, v, v, 255)


def board(px, rnd, size):
    for y in range(size):
        for x in range(size):
            v = 222 + ((y * 7 + x) % 5) * 4 + rnd.randrange(6)
            px[x, y] = (v, v, v, 255)


def rim(px, rnd, size):
    for y in range(size):
        for x in range(size):
            v = 236 - ((x + y) % 3) * 12
            px[x, y] = (v, v, v, 255)


def metal(px, rnd, size):
    for y in range(size):
        for x in range(size):
            px[x, y] = IRON[min(3, (x // 4 + y // 5 + rnd.randrange(2)) % 4)] + (255,)


def wax(px, rnd, size):
    for y in range(size):
        for x in range(size):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            px[x, y] = WAX[2 if d < 3 else 1 if d < 6 else 0] + (255,)


def noticeboard(px, rnd, size):
    wood(px, rnd, size)
    for (x0, y0, x1, y1) in ((2, 2, 7, 8), (8, 3, 14, 9), (4, 9, 11, 14)):
        for y in range(y0, y1):
            for x in range(x0, x1):
                px[x, y] = PARCHMENT[(x + y) % 4] + (255,)
        for y in range(y0 + 1, y1 - 1, 2):
            for x in range(x0 + 1, x1 - 1):
                if rnd.random() < 0.7: px[x, y] = INK + (255,)
        px[(x0 + x1) // 2, y0] = WAX[2] + (255,)


def banner_cloth_item(px, rnd, size):
    for y in range(2, 13):
        for x in range(4, 13):
            v = 228 + ((x + y) % 2) * 10
            px[x, y] = (v, v, v, 255)


def banner_trim_item(px, rnd, size):
    for x in range(4, 13):
        for y in (11, 12):
            px[x, y] = (232, 232, 232, 255)


def banner_pole_item(px, rnd, size):
    for y in range(1, 16):
        px[3, y] = WALNUT[1] + (255,)
    for x in range(2, 14):
        px[x, 1] = WALNUT[2] + (255,)


def shield_board_item(px, rnd, size):
    for y in range(2, 14):
        half = 5 if y < 10 else 5 - (y - 9)
        for x in range(8 - half, 8 + half + 1):
            v = 226 + ((x + y) % 3) * 6
            px[x, y] = (v, v, v, 255)


def shield_rim_item(px, rnd, size):
    for y in range(2, 14):
        half = 5 if y < 10 else 5 - (y - 9)
        for x in (8 - half, 8 + half):
            px[x, y] = (238, 238, 238, 255)
    for x in range(3, 14):
        px[x, 2] = (238, 238, 238, 255)


def charter(px, rnd, size, sealed=False):
    for y in range(3, 13):
        for x in range(3, 13):
            px[x, y] = PARCHMENT[(x * 2 + y) % 4] + (255,)
    for x in range(2, 14):
        px[x, 2] = PARCHMENT[3] + (255,); px[x, 13] = PARCHMENT[3] + (255,)
    for y in range(5, 11, 2):
        for x in range(5, 11):
            if rnd.random() < 0.8: px[x, y] = INK + (255,)
    if sealed:
        for (x, y) in ((10, 11), (11, 11), (10, 12), (11, 12), (9, 12), (12, 12), (10, 13), (11, 13)):
            px[x, y] = WAX[1 if (x + y) % 2 else 2] + (255,)


def main():
    texture('block/kingdom_banner_pole', wood)
    texture('block/kingdom_banner_cloth', cloth)
    texture('block/kingdom_banner_trim', trim)
    texture('block/heraldic_shield_board', board)
    texture('block/heraldic_shield_rim', rim)
    texture('block/court_noticeboard', noticeboard)
    texture('block/seal_press_iron', metal)
    texture('block/seal_press_wood', wood)
    texture('block/seal_press_wax', wax)
    texture('item/kingdom_banner_cloth', banner_cloth_item)
    texture('item/kingdom_banner_trim', banner_trim_item)
    texture('item/kingdom_banner_pole', banner_pole_item)
    texture('item/heraldic_shield_board', shield_board_item)
    texture('item/heraldic_shield_rim', shield_rim_item)
    texture('item/kingdom_charter', lambda px, rnd, size: charter(px, rnd, size))
    texture('item/kingdom_charter_sealed', lambda px, rnd, size: charter(px, rnd, size, sealed=True))

    def element(frm, to, tex, tint=None, faces=('north', 'south', 'east', 'west', 'up', 'down')):
        face = {}
        for name in faces:
            entry = {'texture': '#' + tex}
            if tint is not None: entry['tintindex'] = tint
            face[name] = entry
        return {'from': frm, 'to': to, 'faces': face}

    models = ASSETS / 'models'
    write_json(models / 'block/kingdom_banner.json', {'parent': 'minecraft:block/block', 'textures': {
        'particle': 'mcakingdoms:block/kingdom_banner_pole', 'pole': 'mcakingdoms:block/kingdom_banner_pole',
        'cloth': 'mcakingdoms:block/kingdom_banner_cloth', 'trim': 'mcakingdoms:block/kingdom_banner_trim'}, 'elements': [
        element([7.5, 0, 7.5], [8.5, 16, 8.5], 'pole'), element([2, 14.5, 7.5], [14, 15.5, 8.5], 'pole'),
        element([2.5, 3, 7.75], [13.5, 14.5, 8.25], 'cloth', 0), element([2.5, 3, 7.7], [13.5, 4.5, 8.3], 'trim', 1)]})
    write_json(models / 'block/heraldic_shield.json', {'parent': 'minecraft:block/block', 'textures': {
        'particle': 'mcakingdoms:block/heraldic_shield_board', 'board': 'mcakingdoms:block/heraldic_shield_board', 'rim': 'mcakingdoms:block/heraldic_shield_rim'}, 'elements': [
        element([1, 1, 14], [15, 15, 16], 'board', 0),
        element([1, 14, 13.75], [15, 15, 14], 'rim', 1), element([1, 1, 13.75], [15, 2, 14], 'rim', 1),
        element([1, 2, 13.75], [2, 14, 14], 'rim', 1), element([14, 2, 13.75], [15, 14, 14], 'rim', 1)]})
    write_json(models / 'block/court_noticeboard.json', {'parent': 'minecraft:block/block', 'textures': {
        'particle': 'mcakingdoms:block/court_noticeboard', 'board': 'mcakingdoms:block/court_noticeboard', 'edge': 'mcakingdoms:block/kingdom_banner_pole'}, 'elements': [
        {'from': [0, 1, 14], 'to': [16, 15, 16], 'faces': {'north': {'texture': '#board'}, 'south': {'texture': '#edge'}, 'east': {'texture': '#edge'},
                                                           'west': {'texture': '#edge'}, 'up': {'texture': '#edge'}, 'down': {'texture': '#edge'}}}]})
    write_json(models / 'block/seal_press.json', {'parent': 'minecraft:block/block', 'textures': {
        'particle': 'mcakingdoms:block/seal_press_iron', 'iron': 'mcakingdoms:block/seal_press_iron', 'wood': 'mcakingdoms:block/seal_press_wood', 'wax': 'mcakingdoms:block/seal_press_wax'}, 'elements': [
        element([2, 0, 2], [14, 3, 14], 'wood'), element([5, 3, 5], [11, 4, 11], 'wax'), element([7, 3, 3], [9, 13, 5], 'iron'),
        element([7, 11, 5], [9, 13, 10], 'iron'), element([6, 7, 6], [10, 11, 10], 'iron'), element([3, 12, 7], [13, 13, 9], 'wood')]})
    for block in ('kingdom_banner', 'heraldic_shield', 'court_noticeboard', 'seal_press'):
        write_json(ASSETS / f'blockstates/{block}.json', {'variants': {f'facing={f}': {'model': f'mcakingdoms:block/{block}', **({'y': y} if y else {})}
                                                                        for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}})
    write_json(models / 'item/kingdom_banner.json', {'parent': 'minecraft:item/generated', 'textures': {
        'layer0': 'mcakingdoms:item/kingdom_banner_cloth', 'layer1': 'mcakingdoms:item/kingdom_banner_trim', 'layer2': 'mcakingdoms:item/kingdom_banner_pole'}})
    write_json(models / 'item/heraldic_shield.json', {'parent': 'minecraft:item/generated', 'textures': {
        'layer0': 'mcakingdoms:item/heraldic_shield_board', 'layer1': 'mcakingdoms:item/heraldic_shield_rim'}})
    write_json(models / 'item/court_noticeboard.json', {'parent': 'mcakingdoms:block/court_noticeboard'})
    write_json(models / 'item/seal_press.json', {'parent': 'mcakingdoms:block/seal_press'})
    write_json(models / 'item/kingdom_charter.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'mcakingdoms:item/kingdom_charter'},
                                                       'overrides': [{'predicate': {'mcakingdoms:sealed': 1}, 'model': 'mcakingdoms:item/kingdom_charter_sealed'}]})
    write_json(models / 'item/kingdom_charter_sealed.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'mcakingdoms:item/kingdom_charter_sealed'}})

    copy = {'function': 'minecraft:copy_nbt', 'source': 'block_entity', 'ops': [
        {'source': f'Heraldry.{key}', 'target': f'BlockEntityTag.Heraldry.{key}', 'op': 'replace'} for key in ('Kingdom', 'Primary', 'Secondary')]}
    for block in ('kingdom_banner', 'heraldic_shield', 'court_noticeboard', 'seal_press'):
        entry = {'type': 'minecraft:item', 'name': f'mcakingdoms:{block}'}
        if block != 'seal_press': entry['functions'] = [copy]
        write_json(DATA / f'mcakingdoms/loot_tables/blocks/{block}.json', {'type': 'minecraft:block', 'pools': [
            {'rolls': 1, 'entries': [entry], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}], 'random_sequence': f'mcakingdoms:blocks/{block}'})
    write_json(DATA / 'minecraft/tags/blocks/mineable/axe.json', {'replace': False, 'values': ['mcakingdoms:kingdom_banner', 'mcakingdoms:heraldic_shield', 'mcakingdoms:court_noticeboard']})
    write_json(DATA / 'minecraft/tags/blocks/mineable/pickaxe.json', {'replace': False, 'values': ['mcakingdoms:seal_press']})

    recipes = {
        'kingdom_banner': ({'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['NWN', 'WWW', ' S '],
                            'key': {'N': {'item': 'minecraft:gold_nugget'}, 'W': {'tag': 'minecraft:wool'}, 'S': {'item': 'minecraft:stick'}},
                            'result': {'item': 'mcakingdoms:kingdom_banner'}, 'show_notification': True}, ['minecraft:white_wool'], 'has_wool'),
        'heraldic_shield': ({'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['PNP', 'PPP', ' P '],
                             'key': {'P': {'tag': 'minecraft:planks'}, 'N': {'item': 'minecraft:iron_nugget'}},
                             'result': {'item': 'mcakingdoms:heraldic_shield'}, 'show_notification': True}, ['minecraft:iron_nugget'], 'has_iron_nugget'),
        'court_noticeboard': ({'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['SSS', 'PAP', 'PPP'],
                               'key': {'S': {'item': 'minecraft:stick'}, 'P': {'tag': 'minecraft:planks'}, 'A': {'item': 'minecraft:paper'}},
                               'result': {'item': 'mcakingdoms:court_noticeboard'}, 'show_notification': True}, ['minecraft:paper'], 'has_paper'),
        'seal_press': ({'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': [' K ', 'IHI', 'PPP'],
                        'key': {'K': {'item': 'minecraft:piston'}, 'I': {'item': 'minecraft:iron_ingot'}, 'H': {'item': 'minecraft:honeycomb'}, 'P': {'tag': 'minecraft:planks'}},
                        'result': {'item': 'mcakingdoms:seal_press'}, 'show_notification': True}, ['minecraft:honeycomb'], 'has_honeycomb'),
        'kingdom_charter': ({'type': 'minecraft:crafting_shapeless', 'category': 'misc',
                             'ingredients': [{'item': 'minecraft:paper'}, {'item': 'minecraft:paper'}, {'item': 'minecraft:paper'}, {'item': 'minecraft:ink_sac'}, {'item': 'minecraft:honeycomb'}],
                             'result': {'item': 'mcakingdoms:kingdom_charter'}}, ['minecraft:paper'], 'has_paper'),
    }
    for name, (recipe, unlock, criterion) in recipes.items():
        write_json(DATA / f'mcakingdoms/recipes/{name}.json', recipe)
        write_json(DATA / f'mcakingdoms/advancements/recipes/{name}.json', {'parent': 'minecraft:recipes/root', 'criteria': {
            criterion: {'trigger': 'minecraft:inventory_changed', 'conditions': {'items': [{'items': unlock}]}},
            'has_the_recipe': {'trigger': 'minecraft:recipe_unlocked', 'conditions': {'recipe': f'mcakingdoms:{name}'}}},
            'requirements': [[criterion, 'has_the_recipe']], 'rewards': {'recipes': [f'mcakingdoms:{name}']}})
    print('heraldry resources written')


if __name__ == '__main__':
    main()
