# VozLivre — protótipo Android com Wi-Fi Direct

Esta versão experimental mantém o modo de rede Wi-Fi existente e acrescenta uma primeira integração com Wi-Fi Direct para procurar e ligar dois telemóveis Android sem router nem Internet.

## Como testar
1. Instala o APK nos dois telemóveis Android.
2. Ativa o Wi-Fi nos dois aparelhos (não é necessário ligar a um router).
3. Abre VozLivre e permite o microfone e a localização quando o Android pedir. A permissão de localização é exigida por versões antigas do Android para descobrir dispositivos Wi-Fi Direct.
4. Nos dois telemóveis, toca em **Procurar telemóveis (Wi-Fi Direct)**.
5. Quando aparecer o outro telemóvel, toca em **Ligar por Wi-Fi Direct** num dos aparelhos e aceita os pedidos de ligação que o Android apresentar.
6. Depois de a ligação direta estar estabelecida, espera que os nomes apareçam na lista e toca em **Ligar** para iniciar a chamada.

## Limitações importantes
- Esta é uma versão experimental; a descoberta P2P e o áudio precisam de ser validados em dois dispositivos físicos. Fabricantes e versões de Android podem apresentar comportamentos diferentes.
- Wi-Fi Direct não garante um alcance específico. Mais de 500 metros não é uma expectativa realista entre telemóveis comuns; será necessário equipamento de rádio Wi-Fi de longo alcance e linha de vista para essa fase.
- O áudio e os comandos desta versão não são encriptados. Usa apenas para testes, não para conversas privadas.
- A app deve permanecer aberta durante os testes. Não inclui suporte completo para chamadas em segundo plano.
- O áudio usa PCM16 mono a 16 kHz via UDP. A qualidade depende dos aparelhos e da ligação.

## Compilar APK com GitHub Actions
1. Carrega o conteúdo desta pasta para o repositório GitHub, mantendo a estrutura dos ficheiros.
2. Abre **Actions** e executa **Build VozLivre APK** ou faz push para `main`.
3. Descarrega o artefacto `VozLivre-debug-apk` da execução concluída e extrai `app-debug.apk`.
