# VozLivre — protótipo Android

Protótipo inicial de chamadas de voz locais entre dispositivos Android ligados à mesma rede Wi-Fi, sem necessidade de Internet.

## Funcionalidades deste protótipo
- Descoberta automática por broadcast UDP na rede local.
- Criar/participar informalmente numa sala ao manter a aplicação aberta.
- Chamada individual, atender/rejeitar e terminar.
- Áudio bidirecional PCM de baixa latência via UDP na rede local.

## Limitações importantes
- É um protótipo experimental, não uma aplicação pronta para distribuição.
- A descoberta depende de a rede Wi-Fi permitir broadcast entre clientes. Desative “AP/client isolation” no router se existir.
- A transmissão de áudio não é encriptada nesta versão; utilize apenas numa rede de teste confiável. Não use para conversas privadas.
- O áudio PCM consome mais largura de banda do que codecs como Opus e a qualidade/latência dependem do router e dos dispositivos.
- Esta primeira versão não foi validada em dispositivos físicos neste ambiente. Podem ser necessários ajustes para fabricantes Android específicos.
- O protótipo foi desenhado para uma chamada individual por dispositivo de cada vez; não é ainda uma solução de conferência.
- A aplicação deve permanecer aberta durante os testes. Não há suporte completo para chamadas em segundo plano.

## Compilar APK sem computador local
1. Crie um repositório GitHub e carregue todos os ficheiros deste projeto.
2. No GitHub, abra **Actions** e execute **Build VozLivre APK** (ou faça push para `main`).
3. Abra a execução concluída e descarregue o artefacto `VozLivre-debug-apk`.
4. Extraia o ZIP do artefacto para obter `app-debug.apk`.
5. Instale o APK nos dois Androids e permita acesso ao microfone.

## Testar
1. Ligue ambos os telemóveis ao mesmo router Wi-Fi.
2. O router pode estar sem Internet, mas deve permitir comunicação entre clientes.
3. Abra VozLivre nos dois dispositivos e permita o microfone.
4. Defina nomes diferentes e aguarde alguns segundos para aparecerem na lista.
5. Toque em **Ligar** num dispositivo e aceite a chamada no outro.

## Portas locais
- UDP 39871: descoberta e sinalização.
- UDP 39872: áudio PCM.

## Privacidade
Esta versão transmite áudio sem criptografia. Não a utilize para dados sensíveis. Uma versão pública deve adicionar autenticação, criptografia, tratamento robusto de perda de pacotes e testes de segurança.
