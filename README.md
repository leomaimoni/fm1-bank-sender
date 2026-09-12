# FM1 Bank Sender

App Android simples para enviar bancos DX7 (.syx) para o **M-VAVE FM-1** via cabo USB-OTG.

O FM-1 aceita dumps DX7 padrão (patch único ou banco de 32 vozes) direto por SysEx, sem
precisar colocar o aparelho em nenhum "modo de recepção" — o app só manda os bytes do
arquivo pela porta MIDI USB.

## Como compilar

1. Instale o [Android Studio](https://developer.android.com/studio) (versão recente, Koala ou mais nova).
2. Abra esta pasta (`FM1BankSender`) como projeto no Android Studio ("Open" → selecione a pasta).
3. Deixe o Gradle sincronizar (ele baixa as dependências automaticamente na primeira vez —
   isso acontece na sua máquina, não precisa fazer nada manual).
4. Conecte seu celular Android via USB com a depuração USB ativada (Configurações →
   Sobre o telefone → toque 7x em "Número da versão" para habilitar Opções do desenvolvedor →
   ative "Depuração USB").
5. Clique em **Run ▶** no Android Studio, escolha seu celular como destino.

Isso instala o app diretamente no celular. Depois disso você pode até desconectar do PC —
o app já fica instalado.

## Como usar

1. Copie seus arquivos `.syx` de bancos DX7 para o celular (Downloads, Google Drive, etc — 
   qualquer lugar acessível pelo seletor de arquivos do Android).
2. Abra o app **FM1 Bank Sender**.
3. Conecte o FM-1 ao celular usando um cabo/adaptador USB-OTG (USB-C do celular → USB do FM-1).
4. Na tela do app, toque em **Conectar** ao lado do dispositivo MIDI USB que aparecer
   (deve aparecer o nome do FM-1 assim que ele for reconhecido).
5. Toque em **Adicionar arquivos** e selecione um ou mais `.syx`.
6. Toque em **Enviar** ao lado do banco desejado. O FM-1 vai mostrar a tela de seleção
   de slot (A/B/C/D) para você escolher onde salvar as vozes recebidas.

## Observações técnicas

- O Android reconhece automaticamente qualquer periférico MIDI USB classe-compliant
  (como o FM-1) assim que conectado via OTG — não é necessário nenhum driver adicional.
- O envio é feito em blocos de 512 bytes com uma pequena pausa entre eles, para reduzir
  o risco de perda de dados em adaptadores OTG mais simples. Se notar falhas no envio de
  bancos grandes, tente reduzir `chunkSize` em `MidiUsbHelper.kt` para 256 ou 128.
- O app valida que o arquivo começa com `0xF0` e termina com `0xF7` (marcadores padrão
  de início/fim de SysEx) antes de enviar.
- Testado conceitualmente contra a documentação pública do FM-1; como não tenho o
  hardware aqui para testar de fato, vale confirmar o primeiro envio com um banco de
  teste antes de usar bancos importantes.
