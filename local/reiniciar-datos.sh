#!/usr/bin/env bash
# Borra la base embebida para volver a cargar los datos demo desde cero (detén primero el backend).
rm -rf "$HOME/.garantias360/postgres" && echo "Base embebida eliminada. El próximo arranque cargará los datos demo de nuevo."
