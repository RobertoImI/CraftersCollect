# CraftersCollect v0.1.1 — código fuente

**Minecraft 1.21.1 · NeoForge 21.1.218 · Java 21 · mod server-side**

> Estado: **versión inicial para compilar y probar**, no una versión de producción verificada. Se comprobaron firmas relevantes contra documentación/javadocs de NeoForge 1.21.1 y la sintaxis básica Java, pero **no se pudo ejecutar una compilación Gradle** en el entorno donde se generó este proyecto (sin acceso a Maven/Gradle). El primer paso recomendado es compilar y hacer la prueba con dos jugadores. No incluyo un .jar no probado.

## 1. Estructura

```text
CraftersCollect/
├─ src/main/java/org/crafterscr/crafterscollect/
│  ├─ CraftersCollect.java        Entrada del mod, eventos server-side
│  ├─ CollectData.java           Guardado NBT, eventos, puntos, recompensas e intentos
│  ├─ CollectVisuals.java        Ítems *falsos* enviados sólo al cliente del jugador
│  ├─ CollectService.java        Reloj real, detección de recogida, reclamación segura
│  └─ CollectCommands.java       Comandos Brigadier con selectores vanilla
├─ src/main/resources/META-INF/neoforge.mods.toml
├─ build.gradle, settings.gradle, gradle.properties
├─ gradlew, gradlew.bat          Lanzadores de Gradle 8.10.2 (no oficiales)
├─ .github/workflows/build.yml  Compilación alternativa con GitHub Actions
└─ README.md
```

## 2. Compilación en Windows

1. Instala **JDK 21** (recomendado Eclipse Temurin 21).
2. Descomprime todo el ZIP y entra a la carpeta del proyecto.
3. Abre PowerShell o CMD en esa carpeta.
4. Ejecuta:

```bat
.\gradlew.bat clean build
```

El lanzador descargará Gradle 8.10.2 desde services.gradle.org en el primer uso y Gradle descargará las dependencias de NeoForge desde sus repositorios. Con internet disponible, el jar se generará en:

```text
build/libs/crafterscollect-0.1.1.jar
```

**Alternativa sin ejecutar el lanzador:** instala Gradle 8.10.2 y ejecuta `gradle clean build`. También hay un workflow de GitHub Actions que puede compilarlo automáticamente al subir los archivos a un repositorio.

**Linux/macOS:** `./gradlew clean build` (requiere `curl` o `wget` y `unzip`).

**Instalación:** copia solamente el `.jar` compilado a la carpeta `mods` del servidor NeoForge 1.21.1. Los jugadores **no deben instalar este mod**. No registra bloques/ítems propios ni usa canales de red personalizados.

## 3. Ejemplo de preparación desde cero

Desde un usuario OP, en el mundo:

```mcfunction
/crafterscollect create pascua
/crafterscollect set duration pascua 300
/crafterscollect set attempts pascua 3
/crafterscollect set radius pascua 1.3
```

**Paso A — apariencia**: sostén en la mano principal la cabeza personalizada de un dulce:

```mcfunction
/crafterscollect item add pascua
```

Repite el comando con otras cabezas si quieres modelos variados. Si solo registras una, todos serán iguales. **No consume** el objeto de la mano, copia componentes, incluida la textura personalizada.

**Paso B — posiciones**: ve al lugar donde deba aparecer cada objeto, de pie, y ejecuta:

```mcfunction
/crafterscollect point add pascua
```

Repite tantas veces como quieras: 10, 50, 100, etc. El objeto se representa en el centro horizontal del bloque en que estás parado, a unos 0.35 bloques de altura respecto a tus pies. Cada punto conserva su dimensión.

```mcfunction
/crafterscollect point list pascua
/crafterscollect point preview pascua
/crafterscollect point remove pascua 5
```

`preview` alterna visualización de puntos para el admin; al alejarte seguirán apareciendo los siguientes puntos a una distancia máxima de 48 bloques. Vuelve a ejecutar para ocultarlos.

**Paso C — premios**: sostén en la mano la cantidad EXACTA del premio a entregar. Por ejemplo, 5 diamantes:

```mcfunction
/crafterscollect reward item pascua 0 10
```

A continuación, con el siguiente premio en mano:

```mcfunction
/crafterscollect reward item pascua 11 20
/crafterscollect reward item pascua 21 35
/crafterscollect reward item pascua 36 49
/crafterscollect reward item pascua 50 50
```

Repite un **mismo rango** con otro objeto en mano para incluir varios ítems en el mismo premio. Los rangos distintos no pueden solaparse y el máximo no puede superar los puntos actuales. Para recompensas que deban ejecutar comandos de otros mods:

```mcfunction
/crafterscollect reward command pascua 50 50 give {player} netherite_ingot 1
```

**Importante sobre comandos externos:** se ejecutan como consola del servidor cuando se reclama la recompensa. En Minecraft 1.21.1 este método no retorna confirmación directa de éxito; un comando que simplemente informe un error sin lanzar excepción podría contarse como enviado. Para premios críticos, recomienda usar `reward item` directamente.

## 4. Comandos que ejecuta tu NPC

```mcfunction
/crafterscollect play pascua @p
/crafterscollect claim pascua @p
```

`play`: crea un intento únicamente para el jugador seleccionado. `claim`: comprueba que su reloj haya terminado, verifica el rango y entrega exactamente una vez el premio. **No se reclama mientras esté corriendo el tiempo**.

Admite `@p`, `@s`, `@a` y **nombres de jugadores conectados**, por ejemplo:

```mcfunction
/crafterscollect play pascua Steve
/crafterscollect claim pascua Steve
/crafterscollect play pascua @a
```

**Cuidado:** `@p` significa el jugador más cercano al ORIGEN DEL COMANDO. Si el NPC lo ejecuta desde consola (sin posición), puede elegir mal. Configura tu plugin de NPC para ejecutar el comando desde el NPC o para sustituir el nombre del jugador que activó el botón. `@s` solo funciona si la fuente ejecutora ES el jugador. No existe soporte especial codificado para `@initiator`.

Los comandos están restringidos a permiso nivel 2. Un NPC o bloque de comandos puede actuar como intermediario autorizado: **no concedas OP al jugador común**.

## 5. Mecánica real

- Cada usuario tiene su propio tiempo en reloj de pared. Sigue corriendo incluso desconectado.
- Los objetos visibles son entidades **solo de paquetes de red**, sin entidad persistente de mundo. Los demás participantes no las reciben. Cada cliente las anima como objetos vanilla tirados que giran y flotan. Solo el servidor valida que pasaste por su punto.
- Sonido al iniciar: pling de bloque musical. Al recoger: experiencia. Al terminar/reclamar: subida de nivel; partículas felices solo para ese jugador.
- HUD en Action Bar: recogidos / total y tiempo restante.
- Mientras el tiempo transcurre, no se puede reclamar. **Si recoge TODOS los puntos, la partida termina inmediatamente** y puede reclamar sin esperar el resto del reloj.
- Tras la ronda, los objetos desaparecen y se conserva el resultado del intento para cobrar. Debe cobrar antes de repetir.
- Los premios se entregan directamente al inventario, incluso en creativo (sin destruir sobrantes). Lo que no quepa cae a los pies como ItemEntity REAL protegido con `setTarget(UUID)` y vida útil extendida.
- Si otro mod bloquea la creación del drop, la parte no entregada se guarda en una cola persistente del jugador: el intento se considera reclamado, permite jugar nuevamente y se recupera al liberar espacio ejecutando `/crafterscollect pending pascua Steve`. Si un mod **elimina después** un drop ya creado, esa eliminación externa no puede detectarse con este respaldo.
- Se permite reclamar **uno por intento**. Un jugador puede ganar el mismo rango varias veces en intentos diferentes.
- Los premios se registran con IDs de intento que nunca se reutilizan, aunque se reinicien oportunidades.

## 6. Otros comandos

| Comando | Uso |
|---|---|
| `/crafterscollect list` | Ver eventos |
| `/crafterscollect item list pascua` | Ver apariencias |
| `/crafterscollect item remove pascua 2` | Quitar apariencia #2 |
| `/crafterscollect item clear pascua` | Limpiar apariencias |
| `/crafterscollect point list pascua` | Ver IDs y coordenadas |
| `/crafterscollect point remove pascua 5` | Borrar punto #5 |
| `/crafterscollect reward list pascua` | Ver rangos |
| `/crafterscollect reward remove pascua 0 10` | Borrar rango entero |
| `/crafterscollect results pascua Steve` | Ver resultados e historial reciente |
| `/crafterscollect pending pascua Steve` | Recuperar premios que no pudieron aparecer; requiere espacio libre |
| `/crafterscollect attempts info pascua Steve` | Ver oportunidades |
| `/crafterscollect attempts add pascua Steve 1` | Sumar un intento |
| `/crafterscollect attempts reset pascua Steve` | Reiniciar intentos base usados |
| `/crafterscollect attempts review-close pascua Steve` | Cerrar fallo bajo revisión *sin reentregar ítems* |

El comando `reset` conserva el registro de premios pasados y el contador monotónico de los intentos. No permite reiniciar si el intento actual está jugando, pendiente de reclamar o bajo revisión. Los comandos `attempts` de esta primera versión operan sobre jugadores **conectados**; para la administración de jugadores desconectados ampliaríamos la resolución de UUID en un parche.

## 7. Guardado y seguridad

Almacena datos en la carpeta `data/crafterscollect.dat` del Overworld del servidor. Guarda eventos, puntos de todas las dimensiones, copias de ItemStack, rangos, intentos consumidos, bonos, resultados, recogidas, historial y estado de entrega. El temporizador expira aunque el usuario esté desconectado. El historial de intentos reclamados previos se conserva por UUID.

Protección importante: antes de ejecutar un premio, el intento se marca temporalmente `REVIEW` y se solicita guardar el estado en disco; solo se convierte en `CLAIMED` cuando termina la rutina. Si algo lanza una excepción, permanece `REVIEW`. Así evitamos reintentos automáticos que podrían duplicar ítems; el admin deberá revisar `latest.log` y cerrar el incidente manualmente con `review-close`. Ten en cuenta que no equivale a una transacción atómica de inventario, mundo y comandos externos: siempre hay límites ante un cierre repentino del proceso.

No modifica archivos de Easy NPC, no necesita dependencias de otro mod y no usa ningún paquete de networking propio.

## 8. Lista obligatoria de pruebas en servidor dedicado

- [ ] Compilar con JDK 21 y NeoForge 21.1.218 (o variante 21.1 compatible).
- [ ] Entrar con DOS jugadores sin CraftersCollect instalado en el cliente. A inicia; B no debe ver nada.
- [ ] A recoge un punto; B no debe verlo; si B inicia su juego, ve su propia copia.
- [ ] Cronómetro y Action Bar; finalizar por tiempo; verificar que los visuales se eliminan.
- [ ] Ejecutar `claim` durante partida: debe bloquear. Finalizada: permitir una vez.
- [ ] Premio con inventario lleno en **creativo y supervivencia**: comprobar drop protegido en el suelo y no duplicación.
- [ ] Recoger todos los puntos antes de acabar el tiempo: debe terminar inmediatamente, ocultar visuales y permitir claim.
- [ ] En caso de que un mod bloquee el spawn: comprobar respaldo con `pending`, permitiendo jugar nuevamente sin bloqueo.
- [ ] Desconectar y reconectar durante partida, reiniciar servidor, cambiar dimensión.
- [ ] Repetir 3 intentos con 10/5/50 recogidas: 3 premios independientes.
- [ ] `attempts add` y `attempts reset` conservan el historial.
- [ ] Comprobar `@p` desde tu NPC concreto con otros jugadores cerca.

## 9. Límites de la v0.1.1

- Los objetos se representan con **ítems vanilla falsos** (bobbing y giro natural), no con un `item_display` de escala y transformación personalizada. El tamaño y velocidad de giro todavía no son configurables.
- El sonido, partículas, radio de visualización y colores del HUD son fijos en v0.1.0 (el radio de **recogida** sí es configurable).
- Recompensas de comandos externos: ver advertencia anterior sobre comprobar su ejecución.
- Administración de jugadores desconectados no implementada aún en los comandos.
- No permite cambiar objetos, puntos, duración ni recompensas mientras haya una partida activa del mismo minijuego.
- Los premios pendientes corresponden al rango configurado en el momento del reclamo; evita editar premios con intentos finalizados aún sin cobrar.
- Es imprescindible probar en un servidor real antes de utilizarlo en un evento público.

## 10. Parche v0.1.1 (sobre master)

1. Corrige premios desaparecidos en inventarios creativos llenos: inserción controlada en 36 slots (no usa el comportamiento de borrado de `Inventory.add`).
2. Sobrantes reales: se colocan a los pies del jugador con propietario y tiempo de vida extendido. Si se cancela la creación, quedan guardados para posterior recuperación con `/crafterscollect pending <evento> <jugador>`.
3. No es necesario recoger del suelo el premio previo para jugar otra vez: basta con haber reclamado el intento.
4. Termina inmediatamente al recoger todos los puntos (un intento sigue dando un solo premio).

**Compatibilidad:** la nueva cola opcional `pendingItems` se incorpora al guardado existente: los mundos antiguos sin ese campo cargan con la cola vacía. Haz copia de seguridad del mundo antes de probar cualquier actualización.
