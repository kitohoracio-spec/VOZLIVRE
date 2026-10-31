# VozLivre 0.5

**VozLivre — Desenvolvido por Guido Horácio**

Comunicação local sem Internet: chamadas de voz entre telemóveis Android por Wi-Fi (router ou hotspot), Wi-Fi Direct e Bluetooth, com áudio comprimido (G.711) e cifrado (AES-256-GCM).

## Dois modos

Ao abrir a app pela primeira vez escolhes o modo. Podes mudar mais tarde em Definições, Segundo plano e modo (Servidor), ou em Estado (Cliente).

### Servidor (barra inferior com cinco separadores)

- **Ligações**: visão geral com logótipo, cartão Sessão ativa (online e tempo de sessão) e mosaicos: clientes ligados, chamadas ativas, pedidos pendentes e tipo de rede.
- **Clientes**: separadores Ligados, Pendentes e Histórico. Em Ligados, Ligar a cada cliente e Gerir clientes. Em Pendentes, Aceitar ou Recusar.
- **Chamadas**: separadores Ativas, Recentes e Registos. A chamada ativa tem silenciar, altifalante e terminar, e os outros clientes ficam disponíveis para ligar. Durante uma chamada, uma barra verde acima da navegação leva de volta a este separador.
- **Diagnóstico**: estado geral (Sistema operacional ou o que falha), verificações de rede, descoberta, permissões, sinalização e áudio, botão Executar diagnóstico, tabela de pares e registo com Copiar e Limpar.
- **Definições avançadas**: lista de subpáginas.
  - Gestão da rede: Wi-Fi, ligação por IP, Wi-Fi Direct (criar grupo), Bluetooth, portas e reiniciar motor.
  - Gestão de clientes: aprovação manual, bloquear e desbloquear, limpar bloqueios e aprovações.
  - Gestão de chamadas: regras, estatísticas e histórico.
  - Áudio: volume, microfone, saída e qualidade.
  - Segurança: nome, senha e permissões.
  - Segundo plano e modo.
  - Registos e logs.
  - Sobre.

### Cliente (barra inferior com quatro separadores)

- **Ligação**: logótipo, cartão Ligado ao servidor com botão Desligar, lista de servidores, ligação por IP, Wi-Fi Direct e Bluetooth (recolhidos), nome e senha.
- **Chamadas**: Recentes e Favoritos (estrela), botão Nova chamada que liga ao servidor.
- **Áudio**: volume do altifalante, ganho do microfone, saída de áudio (altifalante ou auricular) e dados de qualidade.
- **Estado**: servidor, tipo de ligação, IP local, força do sinal, tempo de ligação, permissões, segundo plano, modo e Sobre/Créditos.

Em chamada, o Cliente vê o ecrã de chamada em curso com temporizador, microfone, altifalante, Mais e terminar. O Servidor vê o ecrã completo ao ligar e ao receber, e a chamada ativa fica no separador Chamadas.

O Servidor anuncia-se na rede com o papel de servidor. O Cliente lista servidores e dispositivos Bluetooth. Qualquer um dos dois pode receber chamadas do outro. O áudio vai direto entre os dois telemóveis.

## Como testar

1. Instala o APK em dois telemóveis. Num escolhe Servidor, no outro Cliente.
2. Define a **mesma senha** nos dois (Servidor: Definições, Segurança. Cliente: Ligação).
3. Liga os dois à mesma rede, ou usa o hotspot de um deles. Com Wi-Fi Direct, no Servidor abre Gestão da rede e toca em Criar grupo; no Cliente abre Wi-Fi Direct e Bluetooth, toca em Procurar e Ligar.
4. No Cliente toca em Procurar servidores e escolhe o servidor. Depois usa Nova chamada. No Servidor, liga a um cliente em Clientes.

## Se a chamada não acontecer

No Servidor abre Diagnóstico, toca em Executar diagnóstico e depois em Copiar no registo. Envia o texto. Causas frequentes:
- Router com isolamento de clientes: usa hotspot ou Wi-Fi Direct.
- Senhas diferentes: o registo mostra "mensagem não decifrada".
- Poupança de bateria a fechar a app: ativa Segundo plano e retira as restrições de bateria.
- Wi-Fi Direct até ao Android 12 exige a localização ligada.

## Segundo plano

O botão Segundo plano mantém a app à escuta com o ecrã apagado e mostra uma notificação de chamada com ecrã inteiro quando alguém liga. No Android 14 ou superior pode ser preciso permitir notificações em ecrã inteiro nas definições do Android. Gasta mais bateria.

## Segurança

Comandos de chamada e áudio vão cifrados com AES-256-GCM, com chave derivada da senha (PBKDF2-SHA256). Com a senha padrão, qualquer VozLivre na rede consegue decifrar. O anúncio de presença (nome, id e papel) vai em claro para a descoberta funcionar. A aprovação manual de clientes controla quem pode ligar ao servidor, mas não substitui a senha.

## Áudio

PCM16 comprimido com G.711 µ-law. Wi-Fi a 16 kHz, Bluetooth a 8 kHz. Esta versão não fala com as anteriores.

## Limitações

- O código não foi compilado nem testado no ambiente onde foi escrito.
- Uma só chamada de cada vez. O servidor não faz ponte de áudio entre clientes.
- A força do sinal só existe para ligações Wi-Fi a um router ou hotspot; em Wi-Fi Direct e Bluetooth aparece Indisponível.
- Histórico, favoritos e estatísticas: o histórico e as estatísticas existem só durante a sessão; os favoritos ficam guardados.
- Sem Opus: exigiria uma biblioteca nativa.
- Os ícones são vetoriais desenhados à mão e podem precisar de afinação.

## Sobre / Créditos

**VozLivre — Desenvolvido por Guido Horácio**

Comunicação local sem Internet.

## Compilar o APK

Carrega a pasta para um repositório GitHub e executa Actions, Build VozLivre APK. O artefacto `VozLivre-debug-apk` contém `app-debug.apk`. O fluxo fixa o Gradle 8.10.2, exigido pelo plugin Android 8.7.3.
