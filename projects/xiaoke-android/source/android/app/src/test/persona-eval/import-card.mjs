import { readCharacterCard } from '../../../../../src/companion/characterCard.ts';

process.stdin.setEncoding('utf8');
let input = '';
for await (const chunk of process.stdin) input += chunk;
const file = new File([input], 'persona-eval-input.json', { type: 'application/json' });
process.stdout.write(JSON.stringify(await readCharacterCard(file)));
