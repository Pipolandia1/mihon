# Sincronização — configurar o Google Cloud (uma vez só)

> Roteiro do S2 de `docs/DESKTOP_PLAN.md` (§3.4). Leva uns 10 minutos, é feito por você, na sua conta, e
> não custa nada. O app usa só a pasta oculta dele no seu Drive (`drive.appdata`): não vê nenhum outro
> arquivo seu.

## O que você vai precisar

| | |
|---|---|
| Nome do pacote do app no celular | `app.mihon.dev` |
| SHA-1 da chave do fork | `88:93:4E:C7:54:AE:28:53:97:B0:E9:96:2A:59:BD:00:DA:49:42:B6` |
| Escopo | `https://www.googleapis.com/auth/drive.appdata` |

## 1. Criar o projeto

1. Abra <https://console.cloud.google.com/> com a conta Google em que a sincronização vai morar (a mesma
   no celular, no emulador e no PC).
2. No seletor de projetos (no alto, à esquerda) → **Novo projeto** → nome `Mihon Sync` → **Criar**.
3. Confira que o projeto selecionado no alto é o `Mihon Sync` antes de seguir.

## 2. Ligar a API do Drive

1. Menu ☰ → **APIs e serviços** → **Biblioteca**.
2. Procure **Google Drive API** → **Ativar**.

## 3. Tela de consentimento (Google Auth Platform)

1. Menu ☰ → **Google Auth Platform** (ou **APIs e serviços → Tela de consentimento OAuth**) → **Começar**.
2. **Informações do app:** nome `Mihon Sync`, e-mail de suporte = o seu → **Avançar**.
3. **Público-alvo:** **Externo** → **Avançar**.
4. **Dados de contato:** o seu e-mail → **Avançar** → aceite a política → **Criar**.
5. **Acesso a dados** (Data Access) → **Adicionar ou remover escopos** → filtre por `drive.appdata` → marque
   `.../auth/drive.appdata` → **Atualizar** → **Salvar**.
6. **Para já poder testar:** **Público-alvo** (Audience) → **Usuários de teste** → **Adicionar usuários** →
   o seu e-mail → **Salvar**. Em "Teste", o login funciona, mas o Google pede para conectar de novo a cada
   7 dias.
7. **Branding**, que o Google exige para publicar:
   - Página inicial do app: `https://github.com/Pipolandia1/mihon`
   - Política de privacidade: `https://github.com/Pipolandia1/mihon/blob/feature/bubble-zoom/docs/SYNC_PRIVACY.md`
     (esse endereço só existe depois do `git push` da branch com o arquivo `docs/SYNC_PRIVACY.md`)
   - Domínios autorizados: `github.com`
   - **Salvar**.
8. **Público-alvo** → **Publicar app** → confirme. O status deve ficar **Em produção**.
   - Isso é o que impede o login de expirar a cada 7 dias.
   - Com só esse escopo, que o Google classifica como não sensível, não há verificação. Ao entrar, pode
     aparecer um aviso de app não verificado; é o seu próprio app: **Avançado → Acessar Mihon Sync**.
   - Se o Google recusar `github.com` como domínio autorizado (ele exige domínios que sejam seus), o
     caminho é um site no GitHub Pages (`pipolandia1.github.io`) com as duas páginas; avise o Claude.

## 4. Cliente Android (para o celular e o emulador)

1. **Clientes** (Clients) → **Criar cliente**.
2. Tipo: **Android**. Nome: `Mihon Android`.
3. Nome do pacote: `app.mihon.dev`.
4. Impressão digital SHA-1: `88:93:4E:C7:54:AE:28:53:97:B0:E9:96:2A:59:BD:00:DA:49:42:B6`.
5. **Criar**. Não é preciso copiar nada desse cliente: o Android o reconhece pelo pacote e pela chave.

## 5. Cliente para o app do PC (usado no S3, criado agora para não voltar aqui)

1. **Clientes** → **Criar cliente** → tipo **App para computador** (Desktop app) → nome `Mihon Desktop` →
   **Criar**.
2. Na janela que abre, **baixe o JSON** (o Google mostra o segredo uma vez só).
3. Salve o arquivo como `D:\android-build-tools\signing\google-desktop-client.json`, ao lado da chave do
   fork. Não o coloque no repositório e não o cole no chat: o app do PC vai lê-lo de lá.

## 6. Guardar em lugar seguro

Guarde **juntos**, num gerenciador de senhas ou numa pasta privada do Drive:

- `D:\android-build-tools\signing\mihon-fork.jks` (a chave do fork);
- `D:\android-build-tools\signing\keystore.properties` (a senha dela);
- `D:\android-build-tools\signing\google-desktop-client.json` (o cliente do PC).

Sem a chave, nenhum outro PC gera um APK que instale por cima do app do celular, e o login do Google no
celular deixa de funcionar. O Google apaga um cliente OAuth parado por 6 meses; se isso acontecer, refaça os
passos 4 e 5 com os mesmos valores.

## 7. O celular

Antes de instalar o primeiro APK com a chave do fork:

1. No app atual: **Mais → Dados e armazenamento → Criar backup**, e copie o arquivo `.tachibk` para fora do
   celular (Drive, PC).
2. Desinstale o app atual. **Sem o backup, a biblioteca e o Stats se perdem aqui.**
3. Instale o APK novo (o Claude prepara o `app-arm64-v8a-debug.apk`) e, no primeiro uso, **Restaurar
   backup** com o arquivo do passo 1.

## 8. O emulador

No emulador: Configurações do Android → **Contas** → **Adicionar conta → Google**, com a **mesma conta**
do passo 1. Você mesmo digita a senha.
