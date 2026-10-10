# Mihon Sync — Privacy / Privacidade

*Mihon Sync is the sync between devices of a personal fork of the Mihon reader
([github.com/Pipolandia1/mihon](https://github.com/Pipolandia1/mihon)). Last updated: 2026-10-10.*

## English

- **What the app accesses.** Only its own hidden application data folder in your Google Drive
  (`https://www.googleapis.com/auth/drive.appdata`). It cannot see, read or change any other file in your
  Drive, and it reads your account's email address only to show which account is connected.
- **What it stores there.** One file, `mihon-sync.pb.gz`, with your library, chapters read, reading
  history, categories, tracker progress, extension repositories and the app's reading statistics, so your
  other devices running the app can show the same. Passwords, tracker logins and API tokens are never
  included.
- **Who receives it.** Nobody. The file goes from your device straight to your own Google Drive. There
  is no server of the developer, no analytics and no third party.
- **How to remove it.** In the app, *Sync → Disconnect* stops syncing on that device. To delete the file
  and the app's access: Google Drive → *Settings → Manage apps → Mihon Sync → Delete hidden app data*,
  then <https://myaccount.google.com/connections> → *Mihon Sync → Delete all connections*.
- **Contact.** Open an issue at <https://github.com/Pipolandia1/mihon/issues>.

## Português

- **O que o app acessa.** Só a pasta oculta de dados do próprio app no seu Google Drive
  (`https://www.googleapis.com/auth/drive.appdata`). Ele não vê, não lê e não altera nenhum outro arquivo
  do seu Drive, e lê o e-mail da conta só para mostrar qual conta está conectada.
- **O que ele guarda lá.** Um arquivo, `mihon-sync.pb.gz`, com a sua biblioteca, capítulos lidos,
  histórico, categorias, progresso nos rastreadores, repositórios de extensão e as estatísticas de leitura
  do app, para que os seus outros aparelhos com o app mostrem o mesmo. Senhas, logins de rastreadores e
  tokens de API nunca entram.
- **Quem recebe.** Ninguém. O arquivo vai do seu aparelho direto para o seu Google Drive. Não há
  servidor do desenvolvedor, análise de uso nem terceiros.
- **Como remover.** No app, *Sincronização → Desconectar* para de sincronizar naquele aparelho. Para
  apagar o arquivo e o acesso do app: Google Drive → *Configurações → Gerenciar apps → Mihon Sync →
  Excluir dados ocultos do app*, e depois <https://myaccount.google.com/connections> → *Mihon Sync →
  Excluir todas as conexões*.
- **Contato.** Abra uma issue em <https://github.com/Pipolandia1/mihon/issues>.
