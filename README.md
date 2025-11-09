# Monitor de Procesos (Compose for Desktop)

Aplicación de escritorio en **Kotlin + Compose** para **listar procesos** del sistema con:
**ordenación por cabecera**, **filtros** (Proceso/Usuario/Estado), **auto-refresco**,
**detalles** y **exportación a CSV**. Proyecto de **DI/PSP – 2º DAM**.

- **Repositorio:** https://github.com/Rguez00/KotlinProject
- **Memoria (PDF):** ver `Documentacion.pdf` (estructura completa, pruebas y anexos)
- **Descargas listas para usar (artefactos de entrega):**
    - `MonitorDeProcesosWindows.zip` → ejecutable/instalador para **Windows 10/11**
    - `MonitorDeProcesosUbuntu.zip` → binario/paquete para **Ubuntu 22.04/24.04**

> El README es un **resumen**; la explicación detallada está en el PDF.

---

## 🚀 Uso rápido

**Windows**
1. Descarga `MonitorDeProcesosWindows.zip` y descomprímelo.
2. Ejecuta el **.exe** (o el lanzador incluido).  
   En el primer arranque verás “**Cargando procesos…**”.

**Ubuntu**
1. Descarga `MonitorDeProcesosUbuntu.zip` y descomprímelo.
2. Si es binario: `chmod +x ./MonitorDeProcesos` y ejecútalo.  
   Si incluye `.deb/.rpm`, instala con tu gestor de paquetes.

---

## ✨ Qué hace
- **Tabla** con: PID · Proceso · Usuario · CPU% · MEM% · Estado · Ruta
- **Ordenación** por cabecera (asc/desc) con indicador
- **Filtros** por Proceso/Usuario y **Estado** (Running/Other)
- **Auto-refresco** configurable + botón **Refrescar**
- **Detalles** del proceso (PID, usuario, ruta/cmdline, métricas)
- **Exportar CSV** (UTF-8) respetando el **filtro** y la **ordenación** actuales

> Algunas métricas pueden mostrarse **N/D** según permisos/políticas del SO.

---

## 🧩 Tecnologías (resumen)
- **Kotlin/JVM** · **Compose for Desktop**
- Providers por SO:
    - **Windows:** contadores (PowerShell `Get-Counter` / `typeperf`)
    - **Linux:** `ps` + **/proc**
- Empaquetado con `:composeApp:createDistributable`

---

## 🛠️ Desarrollo (opcional)
```bash
# macOS/Linux
./gradlew :composeApp:run

# Windows
.\gradlew.bat :composeApp:run
