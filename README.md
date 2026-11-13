# Orbital Laser — mod para Minecraft Forge 1.20.1

Um item que chama um ataque do céu: o jogador mira um bloco, o céu racha como vidro, um núcleo escuro se forma e cai,
e um feixe abre uma cratera em camadas. Baseado na apresentação *Orbital Laser — Concept Presentation v01* (HUUDA).

- Mod id: `orbital_laser`
- Minecraft 1.20.1, Forge 47.x, Java 17, mapeamentos oficiais
- Jar final: `build/libs/orbital_laser-<versão>.jar` (vai direto na pasta `mods`)

## Como jogar

| Ação | Controle |
|---|---|
| Mirar | Olhe para um bloco a até 256 blocos (aparece um anel ciano com o tamanho da cratera) |
| Travar alvo e carregar | Botão direito com o item |
| Cancelar durante a carga | Agachar + botão direito (não gasta a recarga) |

O item fica na aba **Combate** do modo criativo (ou `/give @s orbital_laser:orbital_laser`).

Sequência: **Mirar → Carga (5 s) → Disparo → Impacto (feixe + onda de choque) → Cratera (cavada em lotes) → Recarga (60 s)**.

### A Mão do Céu (Sky Hand)

Durante a carga, um braço gigante com a pele coberta de escritas quebra o céu por trás:

| Momento (fração da carga) | O que acontece |
|---|---|
| 0–6% | O punho desce por trás do céu, visível apagado através do "vidro" |
| 16% e 36% | Socos 1 e 2 (com antecipação): rachaduras crescem, tela treme, som de impacto |
| 56% | Soco 3: o vidro estoura, cacos caem e o punho atravessa o buraco |
| 60–86% | O braço desce; a mão vai de punho → garra (pose do concept) → palma aberta, dedo por dedo |
| 80–100% | O núcleo escuro se forma na palma, os dedos tremem |
| Disparo | O laser sai da palma, o braço recua com o tranco e os dedos se tensionam |
| Fim | A mão fecha e sobe de volta pelo buraco |

A animação está em `client/hand/HandAnimator.java` (linha do tempo) e `sky_hand_poses.json` (poses).


## Compilar pelo GitHub (recomendado)

O repositório já tem o workflow `.github/workflows/build.yml`. Ele roda em todo push e gera o jar.

1. Crie um repositório vazio no GitHub (ex.: `orbital-laser`), **sem** README nem .gitignore.
2. Envie esta pasta para ele. A forma mais segura é pelo terminal, na pasta do projeto:
   ```bash
   git init
   git add .
   git commit -m "Orbital Laser 1.0.0"
   git branch -M main
   git remote add origin https://github.com/SEU_USUARIO/orbital-laser.git
   git push -u origin main
   ```
   Alternativa sem terminal: **GitHub Desktop** → *File → Add local repository* → escolha a pasta → *Publish repository*.
   Evite o upload pelo navegador: no Mac e no Windows a pasta oculta `.github` costuma ficar de fora, e aí o build não roda.
3. No GitHub, abra a aba **Actions**. O job **Build** leva alguns minutos na primeira vez (baixa Minecraft e Forge).
4. Quando ficar verde, clique no job → seção **Artifacts** → baixe `orbital_laser` (é um .zip com o jar dentro).

### Gerar uma Release com o jar anexado

```bash
git tag v1.0.0
git push origin v1.0.0
```

O workflow compila com a versão da tag (`1.0.0`) e cria uma Release em **Releases** com `orbital_laser-1.0.0.jar`.

## Compilar no seu computador (opcional)

Precisa do **JDK 17** instalado (ex.: Eclipse Temurin 17).

```bash
# Windows
gradlew.bat build
# macOS / Linux
./gradlew build
```

O jar sai em `build/libs/`. Para testar direto num Minecraft de desenvolvimento: `./gradlew runClient`.
A primeira execução baixa Minecraft e Forge e demora alguns minutos.

## Instalar

1. Instale o Forge 1.20.1 (47.x) no launcher.
2. Copie `orbital_laser-1.0.0.jar` para a pasta `mods` (`%appdata%\.minecraft\mods` no Windows).
3. Em servidor, o jar vai no servidor **e** nos clientes (o cliente desenha os efeitos).

## Configuração

**Servidor** (`saves/<mundo>/serverconfig/orbital_laser-server.toml`, ou `world/serverconfig/` no servidor dedicado):

| Opção | Padrão | Faixa | O que faz |
|---|---|---|---|
| `requireOp` | `false` | — | Se `true`, só operadores disparam |
| `opLevel` | 2 | 0–4 | Nível de permissão exigido quando `requireOp` está ligado |
| `maxRange` | 256 | 16–1024 | Distância máxima até o alvo |
| `maxActiveStrikes` | 4 | 1–32 | Ataques simultâneos no servidor |
| `radius` | 24 | 4–96 | Raio da cratera |
| `depth` | 32 | 4–128 | Profundidade no centro |
| `clearAbove` | 24 | 0–128 | Blocos vaporizados acima do alvo (morros, árvores) |
| `irregularity` | 0.12 | 0–0.5 | Irregularidade da borda (0 = círculo perfeito) |
| `terraceHeight` | 4 | 1–16 | Altura de cada degrau da parede (1 = tigela lisa) |
| `chargeSeconds` | 5 | 1–60 | Tempo de carga |
| `cooldownSeconds` | 60 | 0–3600 | Recarga por jogador |
| `blocksPerTick` | 2048 | 64–20000 | Blocos removidos por tick (desempenho) |
| `damage` | 40 | 0–1000 | Dano em quem estiver na área no impacto |
| `protectBlockEntities` | `true` | — | Não destrói baús, fornalhas etc. |
| `fluidsFlowIn` | `true` | — | Água/lava escorrem para dentro da cratera |

**Cliente** (`config/orbital_laser-client.toml`): `renderEffects`, `particles`, `screenShake`, `shakeStrength`, `targetPreview`.

## Regras de segurança (slide 12)

- Bedrock e qualquer bloco inquebrável ficam intactos.
- Com a gamerule `mobGriefing` desligada, o ataque acontece só visualmente, sem destruir terreno.
- Respeita a proteção de spawn e a borda do mundo.
- Os blocos vaporizados não dropam itens (sem duplicação); nenhum dano fora da área da cratera.
- Estado e progresso da escavação ficam salvos (`data/orbital_laser_strikes.dat`): fechar e reabrir o mundo retoma a cratera.
- Morte, saída do jogador ou recarregar o mundo durante a **carga** cancela com segurança (sem gastar a recarga). Depois do disparo, o ataque vai até o fim.
- Toda mudança de estado aparece no log do servidor com o prefixo `[orbital_laser]`.

## Trocar o modelo da mão por um esculpido no Blender

Arquivos em `src/main/resources/assets/orbital_laser/`:

- `models/sky_hand/sky_hand.obj` — malha, um objeto por osso
- `models/sky_hand/sky_hand_rig.json` — hierarquia, pivôs (articulações) e rotação de descanso de cada osso
- `models/sky_hand/sky_hand_poses.json` — poses `fist`, `claw`, `open`, `strain`, `relaxed` (graus `[x, y, z]`; x negativo fecha o dedo)
- `textures/entity/sky_hand.png` — textura (1024×1024)

Para substituir: importe o `sky_hand.obj` no Blender (Y para cima), esculpa cada peça mantendo o nome do objeto
(`arm`, `hand`, `index_1`…`index_3`, `middle_*`, `ring_*`, `pinky_*`, `thumb_*`) e a posição das articulações,
faça o UV para a textura e exporte de volta em OBJ (triangulado, com UV e normais, Y para cima, -Z para frente).
O Minecraft não deforma malha: cada osso é uma peça rígida, então deixe sobreposição nas juntas.
Em jogo, `F3 + T` recarrega o modelo sem reiniciar.

Ferramentas (Python 3 + numpy + Pillow) em `tools/`:

- `hand_generator.py` — gera a malha procedural, o rig e as poses
- `hand_texture.py` — gera a textura com as escritas
- `hand_preview.py` — renderiza uma pose em PNG
- `hand_animatic.py` — gera os quadros do animatic da sequência (vista do chão), para revisar timing sem abrir o jogo

## Estrutura

```
src/main/java/com/huuda/orbitallaser/
  OrbitalLaserMod.java          registro do mod, configs, aba do criativo
  item/OrbitalLaserItem.java     usar / agachar+usar
  strike/StrikeManager.java      estados, validação, cratera em lotes, recarga, persistência
  strike/Strike.java             dados de um ataque e formato da cratera
  network/                       pacote servidor → cliente com a fase do ataque
  client/StrikeRenderer.java     rachaduras no céu, buraco, cacos, mão, núcleo, feixe, onda de choque, anel de mira
  client/hand/HandModel.java     carrega OBJ + rig + poses e desenha a mão
  client/hand/HandAnimator.java  linha do tempo da animação da mão
  client/ClientStrikes.java      coluna de poeira, partículas, tremor de tela
  config/                        configs de servidor e cliente
```

---
Proposta independente da HUUDA, sem afiliação com o mod original, Mojang ou Microsoft.
