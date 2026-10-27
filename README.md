# VozLivre 0.2

Chamadas de voz entre telemóveis Android sem Internet, por três caminhos: rede Wi-Fi (router ou hotspot), Wi-Fi Direct e Bluetooth.

## Como testar

1. Instala o APK nos dois telemóveis e abre a app nos dois. Aceita microfone e notificações.
2. Escolhe o separador e faz o mesmo nos dois aparelhos.
   - **Wi-Fi**: os dois ligados à mesma rede, ou um com hotspot ligado e o outro ligado a ele. A app mostra o IP de cada um; se não se encontrarem, escreve o IP do outro e toca em Procurar.
   - **Wi-Fi Direct**: toca em Procurar nos dois, depois em Ligar num deles e aceita o convite no outro. Quando o grupo se forma, o outro aparece em "Pessoas encontradas".
   - **Bluetooth**: toca em Procurar (e em Tornar visível no outro). Funciona com aparelhos emparelhados ou não.
3. Toca em Ligar ao lado do nome. O outro telemóvel toca; ao atender, a chamada fica ativa com temporizador, silenciar e altifalante.

## Se a chamada não acontecer

Abre **Mostrar diagnóstico** no ecrã principal, toca em **Copiar registo** e envia o texto. O registo mostra as interfaces de rede, os sockets abertos, os pares encontrados e cada mensagem de chamada.

Causas frequentes:
- Router com "isolamento de clientes" ou "AP isolation": bloqueia tudo entre telemóveis. Usa hotspot ou Wi-Fi Direct.
- Poupança de bateria a fechar a app: desativa a otimização de bateria para o VozLivre.
- Wi-Fi Direct em Android até 12 exige a localização ligada.

## Limitações

- O áudio e os comandos não são encriptados.
- Para receber chamadas, a app tem de estar aberta ou em chamada. Não há escuta em segundo plano.
- Wi-Fi: PCM16 16 kHz. Bluetooth: PCM16 8 kHz. Sem compressão.
- Não foi possível compilar nem testar este código no ambiente onde foi escrito; a primeira compilação no GitHub Actions pode mostrar erros pequenos a corrigir.

## Compilar o APK

Carrega a pasta para um repositório GitHub e executa **Actions → Build VozLivre APK**. O artefacto `VozLivre-debug-apk` contém `app-debug.apk`. O fluxo fixa o Gradle 8.10.2, exigido pelo plugin Android 8.7.3.
