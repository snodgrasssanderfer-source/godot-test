import { readCharacterCard } from '../../../../../src/companion/characterCard.ts';

try {
  process.stdin.setEncoding('utf8');
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const file = new File([input], 'persona-eval-input.json', { type: 'application/json' });
  const persona = await readCharacterCard(file);
  process.stdout.write(JSON.stringify(persona));
} catch {
  // A parser exception can contain private input; return only a fixed diagnostic.
  process.stderr.write('Character card import failed.\n');
  process.exitCode = 1;
}
