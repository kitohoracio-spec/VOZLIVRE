# VozLivre 0.4

**VozLivre — Desenvolvido por Guido Horácio**

Chamadas de voz entre telemóveis Android sem Internet, por Wi-Fi (router ou hotspot), Wi-Fi Direct e Bluetooth, com áudio comprimido (G.711) e cifrado (AES-256-GCM).

## Dois modos

Ao abrir a app pela primeira vez escolhes o modo. Podes mudar mais tarde em Definições (Servidor) ou Estado (Cliente).

**Servidor**, com navegação inferior de cinco separadores:
- **Ligações**: estado do servidor, interfaces de rede, ligação por IP, criar grupo Wi-Fi Direct e Bluetooth.
- **Clientes**: lista de clientes encontrados, com Ligar, Bloquear e Desbloquear.
- **Chamadas**: marcação rápida, estatísticas da sessão e histórico.
- **Diagnóstico**: tabela de pares e interfaces, registo de eventos com Copiar e Limpar.
- **Definições**: nome e senha, permissões, segundo plano e bateria, definições avançadas (portas, codec, tempos, reiniciar motor, limpar bloqueios), mudar de modo e Sobre/Créditos.

**Cliente**, com navegação inferior de quatro separadores:
- **Ligação**: procurar servidores, escolher um, ligação por IP, Wi-Fi Direct e Bluetooth (recolhidos), nome e senha.
- **Chamadas**: cartão do servidor escolhido com botão de ligar, e chamadas recentes.
- **Áudio**: microfone, altifalante, volume da chamada e altifalante por defeito.
- **Estado**: indicadores de rede, servidor, microfone, senha e segundo plano, permissões, mudar de modo e Sobre/Créditos.

O Servidor anuncia-se na rede com o papel de servidor. O Cliente só lista servidores (e dispositivos Bluetooth). Qualquer um dos dois pode receber chamadas do outro.

## Como testar

1. Instala o APK em dois telemóveis. Num escolhe Servidor, no outro Cliente.
2. Define a **mesma senha** nos dois (Definições no Servidor, Ligação no Cliente).
3. Liga os dois à mesma rede, ou usa o hotspot de um deles. Alternativamente, no Servidor toca em Criar grupo (Wi-Fi Direct) e no Cliente em Procurar e Ligar.
4. No Cliente toca em Procurar servidores, escolhe o servidor e vai a Chamadas. No Servidor, liga a um cliente em Clientes.

## Se a chamada não acontecer

No Servidor abre Diagnóstico, toca em Copiar e envia o texto do registo. Causas frequentes:
- Router com isolamento de clientes: usa hotspot ou Wi-Fi Direct.
- Senhas diferentes: o registo mostra "mensagem não decifrada".
- Poupança de bateria a fechar a app: ativa Segundo plano e retira as restrições de bateria.
- Wi-Fi Direct até ao Android 12 exige a localização ligada.

## Segundo plano

O botão Segundo plano mantém a app à escuta com o ecrã apagado e mostra uma notificação de chamada com ecrã inteiro quando alguém liga. No Android 14 ou superior pode ser preciso permitir notificações em ecrã inteiro nas definições do Android. Gasta mais bateria.

## Segurança

Comandos de chamada e áudio vão cifrados com AES-256-GCM, com chave derivada da senha (PBKDF2-SHA256). Com a senha padrão, qualquer VozLivre na rede consegue decifrar. O anúncio de presença (nome, id e papel) vai em claro para a descoberta funcionar. Não há autenticação individual dos pares.

## Áudio

PCM16 comprimido com G.711 µ-law. Wi-Fi a 16 kHz, Bluetooth a 8 kHz. Esta versão não fala com as anteriores.

## Limitações

- O código não foi compilado nem testado no ambiente onde foi escrito.
- O design segue a descrição escrita do pedido; a imagem de referência não chegou a ser usada.
- O histórico de chamadas e as estatísticas existem só durante a sessão.
- Sem Opus: exigiria uma biblioteca nativa.
- Os ícones são vetoriais desenhados à mão e podem precisar de afinação.

## Sobre / Créditos

**VozLivre — Desenvolvido por Guido Horácio**

Chamadas de voz sem Internet por Wi-Fi, Wi-Fi Direct e Bluetooth.

## Compilar o APK

Carrega a pasta para um repositório GitHub e executa Actions, Build VozLivre APK. O artefacto `VozLivre-debug-apk` contém `app-debug.apk`. O fluxo fixa o Gradle 8.10.2, exigido pelo plugin Android 8.7.3.
