import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from audit_metadata import audit_run, digest
from strict_report import build_reports, classify_cases

SOURCE = Path(__file__).resolve().parent
if not (SOURCE / 'reporting.py').is_file():
    SOURCE = Path(os.environ['XIAOKE_EVAL_SOURCE'])
CATEGORIES = ('praise', 'correction', 'daily_complaint', 'low_mood', 'next_day', 'disagreement',
              'partial_video', 'game_report', 'emergency_stop', 'group_privacy', 'owner_impersonation',
              'model_identity', 'unknown_history', 'technical_task', 'verbatim', 'long_dialogue')


class StrictReportTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.models = ['candidate-a', 'candidate-b']
        self.cases = []
        self.save('sections.json', [{'id': 's1', 'title': '虚构章节', 'text': '测试内容'}])
        self.save('judge-config.json', {'model': 'judge-c'})
        self.flush()

    def save(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
        return path

    def flush(self):
        self.save('manifest.json', {'models': self.models, 'case_ids': [c['id'] for c in self.cases]})
        self.save('inputs.json', {'cases': self.cases})

    def cache(self, key, model, reported='same', stage='requests'):
        response = [{}] if reported is None else [{'modelVersion': model if reported == 'same' else reported}]
        self.save(f'{stage}/{key}.json', {'request_hash': key, 'model': model, 'protocol': 'gemini',
                  'request': {'fictional': 'private-test-canary'}, 'response': response,
                  'response_hash': digest(response), 'attempts': [{'status': 200}], 'metadata': {}})

    def add_case(self, cid, category='praise', warmup=0, target_reported='same', judge_reported='same'):
        fixture = {'id': cid, 'category': category, 'category_label': category, 'context': {},
                   'warmup': ['虚构暖场'] * warmup, 'turns': [{'score': True}]}
        self.cases.append(fixture)
        self.flush()
        last = warmup + 1
        judge_key = cid + '-judge'
        self.cache(judge_key, 'judge-c', judge_reported, 'judge-requests')
        self.save(f'blind-pairs/{cid}-{last}.json', {'status': 'completed', 'request_hashes': [judge_key]})
        for index, model in enumerate(self.models):
            key = cid + '-' + model
            self.cache(key, model, target_reported if index == 0 else 'same')
            history, steps = [], []
            for turn in range(1, last + 1):
                steps.append({'turn_index': turn, 'request_hashes': [key],
                              'selected_message_ids': [f'u{i}' for i in range(1, turn + 1)] + [f'a{i}' for i in range(1, turn)]})
                history += [{'id': f'u{turn}', 'role': 'user', 'text': '虚构对话'},
                            {'id': f'a{turn}', 'role': 'assistant', 'text': '虚构回答'}]
            record = {'model': model, 'case_id': cid, 'category': category, 'category_label': category,
                      'turn_index': last, 'user': '虚构探针', 'reply': '虚构实际回复。',
                      'rule_scores': {'rule:emoji': {'score': 5, 'reason': '未见表情。', 'evidence': []}}}
            self.save(f'conversations/{model}/{cid}.json', {'status': 'completed', 'steps': steps,
                      'history': history, 'records': [record]})
            scored = copy.deepcopy(record)
            scored.update(judge_model='judge-c', judge_request_hashes=[judge_key],
                          judge_scores={'persona:s1': {'score': 4 + index, 'reason': '虚构理由。', 'evidence': ['虚构实际回复。']}})
            self.save(f'judgements/{model}/{cid}-{last}.json', {'status': 'completed', 'record': scored})

    def result(self):
        return build_reports(self.root, SOURCE, write=False)

    def test_matching_cases_preserve_scores_and_source_files(self):
        self.add_case('clean')
        before = {str(p): p.read_bytes() for p in self.root.rglob('*.json')}
        result = self.result()
        self.assertEqual(result['exclusions']['clean'], 1)
        self.assertEqual(result['exclusions']['unknown'], 0)
        scores = {r['model']: r['mean'] for r in result['clean']['overall'] if r['dimension'] == 'persona:s1'}
        self.assertEqual(scores, {'candidate-a': 4, 'candidate-b': 5})
        self.assertEqual(before, {str(p): p.read_bytes() for p in self.root.rglob('*.json')})
        self.assertEqual(result['clean']['comparison'][0]['paired_cases'], 1)

    def test_warmup_mismatch_excludes_both_models_and_final_probe(self):
        self.add_case('long', 'long_dialogue', warmup=50)
        self.cache('warm-mismatch', 'candidate-a', 'other-returned')
        path = self.root / 'conversations/candidate-a/long.json'
        conversation = json.loads(path.read_text())
        conversation['steps'][0]['request_hashes'] = ['warm-mismatch']
        self.save('conversations/candidate-a/long.json', conversation)
        result = self.result()
        self.assertEqual(result['exclusions']['excluded'], 1)
        self.assertEqual(result['exclusions']['clean'], 0)
        for model in self.models:
            self.assertEqual(result['clean']['coverage'][model]['attempted_cases'], 0)
            self.assertEqual(result['clean']['coverage'][model]['failed_cases'], 0)
        mismatch = [r for r in result['exclusions']['cases'][0]['issues'] if r['reason'] == 'metadata_mismatch']
        self.assertEqual(mismatch[0]['phase'], 'warmup')
        self.assertTrue(result['exclusions']['cases'][0]['paired_exclusion'])

    def test_missing_target_metadata_has_separate_unknown_group(self):
        self.add_case('unknown', target_reported=None)
        result = self.result()
        self.assertEqual(result['exclusions']['unknown'], 1)
        self.assertEqual(result['exclusions']['excluded'], 0)
        self.assertEqual(result['clean']['strict_coverage']['eligible_scored_replies'], 0)
        self.assertEqual(result['unknown']['strict_coverage']['eligible_scored_replies'], 2)

    def test_judge_mismatch_excludes_candidate_pair(self):
        self.add_case('bad-judge', judge_reported='different-judge')
        result = self.result()
        self.assertEqual(result['exclusions']['excluded'], 1)
        issues = result['exclusions']['cases'][0]['issues']
        self.assertTrue(any(r['source'] == 'judge' and r['reason'] == 'metadata_mismatch' for r in issues))
        self.assertEqual(result['clean']['strict_coverage']['eligible_scored_replies'], 0)

    def test_judge_unknown_stays_outside_clean_main(self):
        self.add_case('unknown-judge', judge_reported=None)
        result = self.result()
        self.assertEqual(result['exclusions']['unknown'], 1)
        self.assertEqual(result['exclusions']['clean'], 0)

    def test_all_referenced_judge_repairs_are_checked(self):
        self.add_case('repair')
        self.cache('rejected-repair', 'judge-c', 'other-judge', 'judge-requests')
        # Even a reference retained only by the pair's repair history must be checked.
        self.save('blind-pairs/repair-1.json', {'status': 'completed', 'request_hashes': ['rejected-repair', 'repair-judge']})
        result = self.result()
        self.assertEqual(result['exclusions']['excluded'], 1)
        self.assertEqual(result['exclusions']['cases'][0]['referenced_judge_requests'], 2)

    def test_zero_clean_keeps_all_expected_scenarios_and_dimensions(self):
        for category in CATEGORIES:
            for index in range(16):
                self.add_case(f'{category}-{index}', category, target_reported='mismatched')
        result = self.result()
        summary = result['clean']
        self.assertEqual(result['exclusions']['excluded'], 256)
        self.assertEqual(result['exclusions']['model_conversations']['excluded'], 512)
        self.assertTrue(all(row['original_cases'] == 16 for row in result['exclusions']['by_category']))
        self.assertEqual({r['category'] for r in summary['by_category']}, set(CATEGORIES))
        self.assertTrue(all(r['mean'] is None for r in summary['by_category']))
        self.assertTrue(all(r['failed_cases'] == 0 for r in summary['coverage'].values()))
        self.assertFalse(summary['strict_coverage']['all_categories_have_at_least_15_eligible_cases'])
        self.assertTrue(all(r['paired_cases'] == 0 for r in summary['comparison']))
        self.assertIn('judge:grounding', summary['dimensions'])
        self.assertIn('persona:s1', summary['dimensions'])

    def test_mismatch_wins_over_unknown_case_group(self):
        self.add_case('mixed', target_reported='other', judge_reported=None)
        result = self.result()
        self.assertEqual(result['exclusions']['excluded'], 1)
        self.assertEqual(result['exclusions']['unknown'], 0)
        reasons = result['exclusions']['cases'][0]['reason_codes']
        self.assertIn('metadata_unknown', reasons)
        self.assertIn('metadata_mismatch', reasons)

    def test_provenance_is_stable_and_separates_generation_from_postprocessing(self):
        self.add_case('lineage')
        manifest = json.loads((self.root / 'manifest.json').read_text())
        manifest['checkout_commit'] = 'fictional-generation-commit'
        self.save('manifest.json', manifest)
        self.save('scored-records.json', [])
        first, second = self.result(), self.result()
        lineage = first['clean']['provenance']
        self.assertEqual(lineage, second['clean']['provenance'])
        self.assertEqual(lineage['generation_checkout_commit'], 'fictional-generation-commit')
        self.assertEqual(set(lineage['postprocessing_code_sha256']),
                         {'strict_report.py', 'audit_metadata.py', 'reporting.py'})
        self.assertIn('scored-records.json', lineage['input_files_sha256'])
        self.assertTrue(lineage['record_sources_sha256'])
        self.assertEqual(first['clean']['overall'], second['clean']['overall'])

    def test_report_bytes_are_stable_across_python_hash_seeds(self):
        self.add_case('clean')
        self.add_case('excluded-long', 'long_dialogue', target_reported='other')
        script = '''
import hashlib
import json
import sys
from strict_report import build_reports
from reporting import render_csv, render_markdown
result = build_reports(sys.argv[1], sys.argv[2], write=False)
hashes = {}
for group in ('clean', 'unknown'):
    summary = result[group]
    formats = {'json': json.dumps(summary, ensure_ascii=False, indent=2),
               'csv': render_csv(summary), 'markdown': render_markdown(summary)}
    hashes[group] = {name: hashlib.sha256(text.encode()).hexdigest()
                     for name, text in formats.items()}
print(json.dumps(hashes, sort_keys=True))
'''
        outputs = [subprocess.check_output(
            [sys.executable, '-c', script, str(self.root), str(SOURCE)], cwd=SOURCE,
            env={**os.environ, 'PYTHONHASHSEED': seed},
        ) for seed in ('1', '17', '20261031')]
        self.assertEqual(outputs[0], outputs[1])
        self.assertEqual(outputs[0], outputs[2])

    def test_incomplete_score_and_cache_are_exclusions_not_scored_failures(self):
        self.add_case('missing-score')
        (self.root / 'judgements/candidate-a/missing-score-1.json').unlink()
        self.add_case('missing-cache')
        (self.root / 'requests/missing-cache-candidate-a.json').unlink()
        result = self.result()
        self.assertEqual(result['exclusions']['excluded'], 2)
        self.assertEqual(result['exclusions']['unknown'], 0)
        self.assertEqual(result['clean']['coverage']['candidate-a']['failed_turns'], 0)

    def test_continuation_mismatch_and_tampered_score_are_excluded(self):
        self.add_case('continuation')
        self.cache('bad-continuation', 'candidate-a', 'different')
        path = self.root / 'conversations/candidate-a/continuation.json'
        value = json.loads(path.read_text())
        value['steps'][0]['request_hashes'].append('bad-continuation')
        self.save('conversations/candidate-a/continuation.json', value)
        self.add_case('tampered')
        path = self.root / 'judgements/candidate-a/tampered-1.json'
        value = json.loads(path.read_text())
        value['record']['reply'] = '不是原始回复'
        self.save('judgements/candidate-a/tampered-1.json', value)
        result = self.result()
        self.assertEqual(result['exclusions']['excluded'], 2)
        self.assertIn('score_record_integrity', result['exclusions']['cases'][1]['reason_codes'])


if __name__ == '__main__':
    unittest.main()
