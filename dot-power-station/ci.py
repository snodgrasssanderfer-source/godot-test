import asyncio,hashlib,json,os,pathlib,shutil,subprocess,sys,time,urllib.request,zipfile
R=pathlib.Path(__file__).resolve().parent
OUT=R/'output'; WORK=R/'work'; VERSION='4.7.2-stable'
def fetch(name):
 p=R/name
 with urllib.request.urlopen('https://github.com/godotengine/godot-builds/releases/download/'+VERSION+'/'+name,timeout=120) as src,p.open('wb') as dst:shutil.copyfileobj(src,dst)
 return p
def prepare():
 OUT.mkdir(exist_ok=True);WORK.mkdir(exist_ok=True)
 manifest=json.loads((R/'manifest.json').read_text());data=b''.join((R/n).read_bytes() for n in manifest['parts'])
 assert hashlib.sha256(data).hexdigest()==manifest['sha256'],'Source archive checksum mismatch'
 archive=OUT/'power-station-source.zip';archive.write_bytes(data)
 with zipfile.ZipFile(archive) as z:z.extractall(WORK)
 sums=fetch('SHA512-SUMS.txt').read_text()
 for name in [f'Godot_v{VERSION}_linux.x86_64.zip',f'Godot_v{VERSION}_export_templates.tpz']:
  p=fetch(name);line=next(l for l in sums.splitlines() if l.split()[-1].lstrip('*')==name)
  h=hashlib.sha512()
  with p.open('rb') as f:
   for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
  assert h.hexdigest()==line.split()[0],name+' failed SHA512'
  with zipfile.ZipFile(p) as z:
   if name.endswith('.zip'):z.extractall(R/'godot')
   else:
    d=pathlib.Path.home()/'.local/share/godot/export_templates/4.7.2.stable';d.mkdir(parents=True,exist_ok=True)
    for n in z.namelist():
     if pathlib.Path(n).name in ['android_debug.apk','android_release.apk','version.txt']:(d/pathlib.Path(n).name).write_bytes(z.read(n))
 godot=R/'godot'/f'Godot_v{VERSION}_linux.x86_64';godot.chmod(0o755)
 sdk=pathlib.Path(os.environ.get('ANDROID_HOME',os.environ.get('ANDROID_SDK_ROOT','/usr/local/lib/android/sdk')))
 java=pathlib.Path(os.environ.get('JAVA_HOME_17_X64',os.environ['JAVA_HOME']))
 assert (sdk/'platform-tools/adb').exists(),'Runner Android SDK unavailable'
 key=R/'debug.keystore'
 subprocess.run([str(java/'bin/keytool'),'-genkeypair','-keystore',str(key),'-storepass','android','-alias','androiddebugkey','-keypass','android','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug,O=Android,C=US'],check=True)
 cfg=pathlib.Path.home()/'.config/godot';cfg.mkdir(parents=True,exist_ok=True)
 (cfg/'editor_settings-4.7.tres').write_text('[gd_resource type="EditorSettings" format=3]\n\n[resource]\nexport/android/android_sdk_path = '+json.dumps(str(sdk))+'\nexport/android/java_sdk_path = '+json.dumps(str(java))+'\n')
 (R/'paths.json').write_text(json.dumps({'godot':str(godot),'sdk':str(sdk),'java':str(java),'key':str(key)}))
 print('Official Godot and templates verified. Source reconstructed.',flush=True)
async def build():
 from mcp import ClientSession,StdioServerParameters
 from mcp.client.stdio import stdio_client
 p=json.loads((R/'paths.json').read_text());project=WORK/'android-project';mcp=R/'mcp/node_modules/@yanhuifair/godot-mcp'
 cli=mcp/'dist/utils/godot_cli.js';s=cli.read_text();old="['--path', projectPath, '--export', preset, outputPath]";new="['--headless', '--path', projectPath, '--export-release', preset, outputPath]"
 assert old in s or new in s,'Unexpected MCP exporter implementation'
 cli.write_text(s.replace(old,new));env=dict(os.environ,GODOT_PATH=p['godot'],JAVA_HOME=p['java'])
 async with stdio_client(StdioServerParameters(command='node',args=[str(mcp/'dist/index.js'),'-p',str(project)],env=env)) as (rd,wr):
  async with ClientSession(rd,wr) as session:
   await session.initialize()
   options={'architectures/armeabi-v7a':'false','architectures/arm64-v8a':'true','architectures/x86':'false','architectures/x86_64':'false','package/unique_name':'"org.dot.powerstation.lab"','package/name':'"废站供电"','package/signed':'true','version/code':'1','version/name':'"1.0-test"','permissions/internet':'false','keystore/release':json.dumps(p['key']),'keystore/release_user':'"androiddebugkey"','keystore/release_password':'"android"'}
   result=await session.call_tool('create_export_preset',{'name':'Android','platform':'Android','export_path':str(OUT/'power-station-android.apk'),'exclude_filter':'build/*,tools/*','options':options});assert not result.isError,result
   result=await session.call_tool('export_project',{'preset':'Android','output_path':str(OUT/'power-station-android.apk')});assert not result.isError,result
   print(result.model_dump_json(),flush=True)
   end=time.monotonic()+600
   while time.monotonic()<end:
    await asyncio.sleep(3);result=await session.call_tool('monitor_output',{'clear':False});text='\n'.join(c.text for c in result.content if c.type=='text')
    if 'Process exited' in text:
     print(text,flush=True);assert 'Process exited with code 0' in text,'Godot export failed';break
   else:raise RuntimeError('MCP export did not complete in 10 minutes')
 apk=OUT/'power-station-android.apk';assert apk.stat().st_size>1000000
 sdk=pathlib.Path(p['sdk']);bt=sorted((sdk/'build-tools').iterdir(),key=lambda x:[int(n) if n.isdigit() else 0 for n in x.name.split('.')])[-1]
 subprocess.run([str(bt/'apksigner'),'verify','--verbose',str(apk)],check=True)
 subprocess.run([str(bt/'zipalign'),'-c','-p','4',str(apk)],check=True)
 with zipfile.ZipFile(apk) as z:assert z.testzip() is None
 (OUT/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name+'\n' for f in [apk,OUT/'power-station-source.zip']))
 print('MCP export, APK signing and archive checks passed.',flush=True)
if sys.argv[1]=='prepare':prepare()
else:asyncio.run(build())
