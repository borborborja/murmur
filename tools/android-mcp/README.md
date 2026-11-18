# Connector local de Murmur per a Android

Aquest pont permet que Claude Desktop i Claude Code consultin directament el telèfon. No necessita Murmur d’escriptori ni un servidor públic. L’ordinador i el telèfon han de compartir una xarxa local Wi-Fi o Ethernet.

## Preparació

Cal Node.js 20 o posterior. El pont no té dependències npm. Les credencials es desen al clauer del sistema: Keychain a macOS, Credential Manager a Windows o Secret Service a Linux. A Linux cal tenir `secret-tool` instal·lat i un clauer desbloquejat.

1. Al telèfon, obre **Configuració > Connector Claude**, activa’l i prem **Connecta un client**.
2. A l’ordinador, executa des d’aquesta carpeta:

   ```sh
   node bridge.mjs pair
   ```

3. Enganxa el codi `murmur-mcp://pair?...` que mostra el telèfon al camp interactiu. No el passis com a argument d’una ordre: és un secret temporal.
4. Accepta la petició al telèfon. El codi caduca als cinc minuts i només serveix per a un client.
5. L’ordre mostra la configuració concreta de Claude Desktop i l’ordre de Claude Code. Cap de les dues conté el token.

El fitxer de configuració de Claude Desktop ha de conservar les altres entrades que ja tinguis. L’estructura de la nova entrada és:

```json
{
  "mcpServers": {
    "murmur-android": {
      "command": "/ruta/absoluta/a/node",
      "args": ["/ruta/absoluta/al/projecte/tools/android-mcp/bridge.mjs", "serve", "--device", "identificador-del-dispositiu"]
    }
  }
}
```

Per a Claude Code, utilitza l’ordre mostrada després d’aparellar o:

```sh
claude mcp add murmur-android -- /ruta/absoluta/a/node /ruta/absoluta/bridge.mjs serve --device identificador-del-dispositiu
```

Aquest és un servidor MCP local per stdio. Els connectors remots del núvol de Claude no poden arribar a una adreça privada del telèfon i no substitueixen aquesta configuració.

## Eines

- `list_meetings`: llista les reunions del telèfon.
- `search_meetings`: cerca als títols, notes i transcripcions.
- `get_meeting`: recupera la reunió en Markdown.
- `transcribe_file`: rep una ruta absoluta d’àudio o vídeo a l’ordinador, puja el fitxer per la connexió local xifrada i retorna un identificador de feina. Màxim 512 MiB.
- `get_transcription`: consulta l’estat de la feina i recupera la transcripció quan ha acabat.

L’àudio que s’importa va directament de l’ordinador al telèfon, sense passar per Anthropic. Claude rep els resultats de les eines, que poden contenir el text de la reunió.

## Confiança, xarxa i baixa

La confiança es fixa en el certificat mostrat al codi d’aparellament. El pont comprova el seu SHA-256 abans d’enviar cap capçalera d’autorització. mDNS ajuda a trobar una adreça o port nou, però mai estableix confiança per si mateix. Un certificat diferent exigeix tornar a aparellar.

Cada client té un token propi, revocable al telèfon. L’app desa només el seu verificador; el token de l’ordinador viu al clauer. El servei s’atura si desapareix la xarxa local i mostra una notificació amb el botó d’aturar.

```sh
node bridge.mjs list
node bridge.mjs forget --device identificador-del-dispositiu
```

`forget` elimina la credencial local. Retira també el client a l’app Android per revocar-ne l’accés.

## Verificació

```sh
npm test
```

Les proves cobreixen HTTPS real amb certificats temporals, la negativa a enviar credencials a un certificat diferent, l’aprovació d’aparellament, la transferència de bytes d’un fitxer, la configuració sense secrets, stdio i missatges mDNS malformats. Les proves de la política Android són a `McpAccessTest`. Aquestes proves no acrediten encara una connexió real entre Claude i un telèfon físic.
