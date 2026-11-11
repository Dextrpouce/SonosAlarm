"""Test minimal des alarmes Sonos via SoCo (à lancer sur un ordinateur du même réseau Wi-Fi que l'enceinte).

Installation :  pip install soco
Usage       :  python test_alarme_era100.py 192.168.1.50

Ce script ne fait que : lister les alarmes, créer une alarme de test désactivée,
la relister, puis la supprimer. Aucune alarme existante n'est modifiée.
"""

import sys
from datetime import time

import soco
from soco.alarms import Alarm, get_alarms


def main(ip):
    zone = soco.SoCo(ip)
    print(f"Enceinte : {zone.player_name} ({zone.get_speaker_info().get('model_name', '?')})")

    print("\n1) Alarmes existantes :")
    for a in get_alarms(zone):
        print(f"   ID={a.alarm_id} heure={a.start_time} récurrence={a.recurrence} activée={a.enabled}")

    print("\n2) Création d'une alarme de test (désactivée, 23:59) :")
    test = Alarm(zone, start_time=time(23, 59, 0), recurrence="ONCE", enabled=False, volume=10)
    alarm_id = test.save()
    print(f"   Alarme créée, ID={alarm_id}")

    print("\n3) Relecture :")
    ids = [a.alarm_id for a in get_alarms(zone)]
    print("   Présente" if alarm_id in ids else "   ABSENTE (anomalie)")

    print("\n4) Suppression de l'alarme de test :")
    print("   Supprimée" if test.remove() else "   Échec de la suppression")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage : python test_alarme_era100.py <adresse IP de l'enceinte>")
    main(sys.argv[1])
