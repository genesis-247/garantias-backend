# Borra la base embebida para volver a cargar los datos demo desde cero (detén primero el backend).
Remove-Item -Recurse -Force (Join-Path $HOME ".garantias360\postgres") -ErrorAction SilentlyContinue
Write-Host "Base embebida eliminada. El próximo arranque cargará los datos demo de nuevo."
