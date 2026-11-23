#!/bin/zsh
cd "$(dirname "$0")" || exit 1
/usr/bin/python3 scripts/reiniciar_local.py
resultado=$?
if (( resultado != 0 )); then
  printf '\nNo se pudo completar la operación. Presiona Enter para cerrar.\n'
  read respuesta
fi
exit "$resultado"
