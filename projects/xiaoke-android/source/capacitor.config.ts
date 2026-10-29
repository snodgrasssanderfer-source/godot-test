import type { CapacitorConfig } from '@capacitor/cli'

const config: CapacitorConfig = {
  appId: 'io.github.dshcompanion.mobile',
  appName: '小克助手',
  webDir: 'dist',
  server: {
    androidScheme: 'https',
    cleartext: false,
  },
  android: {
    allowMixedContent: false,
    backgroundColor: '#111315',
    captureInput: true,
    webContentsDebuggingEnabled: false,
  },
}

export default config
