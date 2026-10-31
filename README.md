# VozLivre V6

**VozLivre — Desenvolvido por Guido Horácio**

Comunicação local sem Internet por chamadas de voz entre telemóveis Android. Esta é a primeira fase do V6: projeto modular com dois APKs independentes e credenciamento do Administrador Principal no Servidor.

## Estado desta fase

O código desta fase foi escrito sem compilador Android nem dispositivos disponíveis. Nada foi compilado nem testado em telemóveis. A primeira compilação no GitHub Actions pode mostrar erros pequenos de Kotlin a corrigir, e os testes em dois dispositivos físicos ficam por fazer.

| Parte da especificação | Estado |
|---|---|
| 2. Dois APKs, módulos Gradle, tarefas `assemble*` | Feito. Falta o módulo `data` (a persistência de chamadas vem na fase seguinte). |
| 3. Credenciamento do Administrador (3.1 a 3.7) | Feito, com as diferenças indicadas abaixo. |
| 4. Autorização central de chamadas assinada pelo Servidor | Não feito. Hoje a chamada é direta entre os dois telemóveis, com a senha partilhada. |
| 5. Contas de utilizadores e dispositivos, login do Cliente | Não feito. O Servidor já tem aprovação manual de clientes, bloqueio e permissões por administrador. |
| 6. Transportes (Wi-Fi, Wi-Fi Direct, Bluetooth) | Já existiam. Por testar em dispositivos físicos. |
| 7 e 8. Descoberta (NSD, hotspot, deteção de AP isolation) | Parcial: broadcast da sub-rede, varredura e ligação manual por IP. Falta NSD e a deteção de isolamento. |
| 9. Preparação para a Internet (WebRTC) | Não feito. |
| 10 e 11. Chamadas, áudio, G.711 e AES-256-GCM | Já existiam. A chave ainda vem da senha partilhada, não de chaves por chamada. |
| 12. Histórico central persistente e sincronização | Não feito. O histórico existe só durante a sessão. |
| 13. Métricas reais de qualidade | Não feito (existe só a força do sinal Wi-Fi quando o Android a dá). |
| 14. Teste de Internet no Diagnóstico | Não feito. O documento da especificação chegou cortado na secção 14.1. |

## Estrutura

```
VozLivre/
├── core/          biblioteca partilhada: rede, áudio, cifra de chamadas, motor, componentes visuais
├── app-server/    VozLivre Servidor (com.vozlivre.server): painel, administração, segurança
├── app-client/    VozLivre Cliente (com.vozlivre.client): ligar ao servidor e chamar
├── build.gradle, settings.gradle, gradle.properties
└── .github/workflows/build-apk.yml
```

Tarefas: `assembleServerDebug`, `assembleClientDebug`, `assembleServerRelease`, `assembleClientRelease`.
Artefactos: `VozLivre-Servidor-debug.apk` e `VozLivre-Cliente-debug.apk`.

Os dois APKs instalam-se no mesmo telemóvel. Se os dois estiverem abertos ao mesmo tempo no mesmo aparelho, disputam as portas UDP 39871 e 39872; para testar, abre um de cada vez ou usa telemóveis diferentes.

## Credenciamento do Administrador (só no Servidor)

- **Primeira abertura**: assistente de configuração (utilizador, senha forte, chave de recuperação). Depois de concluído, o assistente nunca mais abre. Se os dados cifrados deixarem de poder ser lidos, a app fica bloqueada em vez de oferecer uma configuração nova, para ninguém tomar o servidor apagando a chave.
- **Identidade do dispositivo**: par de chaves EC P-256 no Android Keystore. No início de sessão o dispositivo assina um desafio aleatório, verificado com a chave pública guardada. Não se usa IMEI, MAC nem Android ID.
- **Senhas**: PBKDF2-HMAC-SHA256 com 310 000 iterações e salt individual. Não foi usado Argon2id, porque o Android não o inclui e não há como obter a biblioteca neste ambiente. Os hashes ficam dentro de um bloco cifrado com AES-256-GCM, com chave no Keystore.
- **Tentativas**: 5 tentativas livres; depois bloqueio de 30 s que duplica até 15 min. O contador é global (não revela se um nome existe) e sobrevive ao reinício.
- **Sessão**: expira por inatividade (1, 5, 10, 30 ou 60 min, por defeito 10) e termina manualmente. Suspender uma conta, redefinir senha ou recuperar o acesso revoga as sessões em curso.
- **Biometria (opcional, Android 10 ou superior)**: atalho de desbloqueio válido 24 h depois de um início de sessão com senha. Não substitui a senha nem a recuperação.
- **Administradores adicionais**: só o Principal os cria. Perfis Secundário e Operador, com permissões por conta (gerir clientes, rede, segurança, auditoria, chamadas). A senha inicial é gerada, mostrada uma única vez e tem de ser trocada no primeiro acesso. Suspender, reativar e revogar são possíveis; ninguém altera o Principal. O Cliente não cria contas.
- **Auditoria**: acessos, falhas, bloqueios, recuperação e alterações de contas ficam num registo em SQLite com cadeia selada por HMAC do Keystore. "Verificar integridade" mostra se alguma entrada foi alterada ou apagada.
- **Capturas de ecrã**: o Servidor bloqueia capturas e miniaturas (FLAG_SECURE), o que também protege a chave de recuperação.

### Recuperação do acesso

1. Na configuração inicial é mostrada uma chave de recuperação, uma única vez. Escreve-a em papel e guarda-a fora do telemóvel. É preciso escrever o primeiro grupo para confirmar que a guardaste. Se a app fechar antes dessa confirmação, a chave não vale e é gerada outra no próximo início de sessão.
2. Se esqueceres a senha: no ecrã de acesso, "Esqueci a senha", chave de recuperação e nova senha.
3. A recuperação cria uma nova identidade do dispositivo (a antiga deixa de valer), revoga todas as sessões, desbloqueia o contador de tentativas e gera uma nova chave de recuperação, que tens de guardar. O evento fica na auditoria.
4. Não existe senha padrão nem porta de entrada universal. Sem a chave de recuperação, a única saída é desinstalar a app, o que apaga as contas e o histórico deste servidor.

### Limitações conhecidas

- Os administradores entram no próprio telemóvel do Servidor. Entrar de outro aparelho exigiria um protocolo de administração remota, que ainda não existe.
- Se o telemóvel do Servidor se perder, a instalação perde-se com ele: não há exportação de cópia de segurança. O mesmo vale se o Keystore for apagado.
- Instalar o APK Servidor noutro telemóvel cria uma instalação nova, com a sua própria configuração inicial, e não herda nada da anterior.
- O relógio do telemóvel serve para o bloqueio por tentativas; quem alterar a hora consegue encurtar uma espera.
- As chamadas locais continuam a usar a senha partilhada do Servidor e dos Clientes (Definições, Segurança). A autorização por chamada, com chaves próprias, é a fase 3.

## Compilar

Carrega a pasta para um repositório GitHub e executa Actions, Build VozLivre APKs. O fluxo fixa o Gradle 8.10.2, exigido pelo plugin Android 8.7.3, e publica os dois APKs.

## Sobre / Créditos

**VozLivre — Desenvolvido por Guido Horácio**
