import hashlib
import json
import re
import argparse
from pathlib import Path
from inspect_disc import Disc

root = Path(__file__).resolve().parents[1]
reference = root / '.tools/references/dw_decomp-main/config'
keys = ['PARTNER_ENTITY', 'PARTNER_PARA', 'INVENTORY', 'TAMER_ENTITY', 'ITEM_PARA', 'DIGIMON_DATA', 'CURRENT_MAP_ID', 'EVO_REQ_DATA', 'EVO_PATHS_DATA', 'MONEY', 'HOUR', 'MINUTE', 'MAP_ENTRIES', 'MAP_NAME_PTR', 'MOVE_NAMES', 'MAP_COLLISION_DATA', 'TAMER_PREVIOUS_TILE_X', 'TAMER_PREVIOUS_TILE_Y', 'NPC_ENTITIES', 'ENTITY_TABLE', 'MAP_DIGIMON_TABLE', 'MAP_WARPS', 'MAIN_D_80124544', 'MAIN_D_80123E6C', 'TOILET_DATA', 'DROPPED_ITEMS', 'WORLD_OBJECTS', 'CHEST_ARRAY']
keys += ['INVENTORY_STATE', 'INVENTORY_OPEN', 'INVENTORY_POINTER', 'GAME_MENU_SPRITES', 'TRIANGLE_MENU_STATE', 'GAME_STATE', 'UI_BOX_DATA', 'TAMER_ITEM', 'COMBAT_DATA_PTR', 'ACTION_CURSOR', 'TAMER_STATE', 'IS_IN_MENU', 'IS_SCRIPT_PAUSED']
keys += ['SCRIPT_DATA_PTR', 'SCRIPT_HEADER_PTR', 'ACTIVE_MAP_SCRIPT', 'CURRENT_SCRIPT_ID']
keys += ['EVOLUTION_TARGET', 'PARTNER_STATE', 'PARTNER_SUB_STATE', 'TAMER_SUBSTATE', 'YEAR', 'DAY', 'HAS_USED_EVOITEM', 'IMMORTAL_HOUR', 'HAS_IMMORTAL_HOUR', 'EVO_GAINS_DATA']
keys += ['CURRENT_SCREEN', 'SCRIPT_STATE_PTR', 'PTR_DIGIMON_FILE_NAMES', 'DIGIMON_SKELETONS']
parser = argparse.ArgumentParser(description="Regenerate RAM metadata using your own reference disc")
parser.add_argument('--disc', required=True, type=Path)
args = parser.parse_args()
profiles = []
for region in ('jp', 'us'):
    symbols = (reference / region / 'symbols.txt').read_text()
    profile = {'id': region, 'label': 'Datos en directo', 'addresses': {}}
    for key in keys:
        match = re.search(r'^' + key + r' = (0x[0-9A-Fa-f]+);', symbols, re.M)
        if not match:
            raise ValueError(key)
        profile['addresses'][key] = int(match[1], 16) & 0x1fffff
    profile['addresses']['EVO_ICON_DATA']=profile['addresses'].pop('MAIN_D_80124544')
    profile['addresses']['EVO_ICON_CLUT']=profile['addresses'].pop('MAIN_D_80123E6C')
    symbols_item=re.search(r'^MAIN_D_80127BDC = (0x[0-9A-Fa-f]+);',symbols,re.M)
    if not symbols_item:raise ValueError('Item icon palette table')
    profile['addresses']['ITEM_ICON_PALETTE']=int(symbols_item[1],16)&0x1fffff
    profiles.append(profile)
disc = Disc(args.disc)
try:
    entry = next(e for e in disc.entries() if e['name'] == 'SLPS_017.97;1')
    exe = disc.read(entry['lba'], entry['size'])
    load = int.from_bytes(exe[24:28], 'little') & 0x1fffff
    profiles[0]['referenceExecutableSha256'] = hashlib.sha256(exe).hexdigest()
    # These static tables are independently compared against live RAM before
    # trusting mutable addresses. The user's binary has the expected JP layout.
    profiles[0]['signatures'] = []
    for name, delta in [('EVO_PATHS_DATA', 0), ('DIGIMON_DATA', 20), ('ITEM_PARA', 20)]:
        address = profiles[0]['addresses'][name] + delta
        data = exe[address-load+2048:address-load+2048+(12 if name == 'ITEM_PARA' else 16)]
        profiles[0]['signatures'].append({'offset': address, 'length': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    # USA layout remains experimental and requires structure checks. No fake
    # fingerprint of an unprovided original ROM is generated.
    profiles[1]['signatures'] = []
finally:
    disc.close()
out = root / 'app/src/main/assets/profiles.json'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(profiles, indent=2), encoding='utf-8')
print(out)
