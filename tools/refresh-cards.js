#!/usr/bin/env node

const fs = require('node:fs/promises');
const crypto = require('node:crypto');
const path = require('node:path');

const SOURCE_URL = 'https://ffxivcollect.com/api/triad/cards';
const CSV_HEADER = ['id', 'public_number', 'name', 'stars', 'type', 'top', 'right', 'bottom', 'left', 'sale_price'];
const TYPES = new Set(['Normal', 'Primal', 'Scion', 'Society', 'Garlean']);

function fail(message) {
  throw new Error(`Invalid card snapshot: ${message}`);
}

function integer(value, field, { min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER } = {}) {
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    fail(`${field} must be an integer from ${min} to ${max}`);
  }
  return value;
}

function text(value, field) {
  if (typeof value !== 'string' || value.trim() === '') fail(`${field} must be nonblank text`);
  return value;
}

function parsePublicNumber(value) {
  text(value, 'number');
  const match = /^(No\. (\d+)|Ex\. (\d+))$/.exec(value);
  if (!match) fail(`invalid public number ${JSON.stringify(value)}`);
  const number = Number(match[2] || match[3]);
  if (match[2]) integer(number, 'public number', { min: 1, max: 460 });
  else integer(number, 'public number', { min: 1, max: 15 });
  return value;
}

function cardFromRecord(record, index) {
  if (!record || typeof record !== 'object' || Array.isArray(record)) fail(`record ${index} is not an object`);
  const id = integer(record.id, `record ${index} id`, { min: 1 });
  const publicNumber = parsePublicNumber(record.number);
  const name = text(record.name, `record ${index} name`);
  const stars = integer(record.stars, `record ${index} stars`, { min: 1, max: 5 });
  const salePrice = integer(record.sell_price, `record ${index} sell_price`, { min: 0 });
  const type = record.type && typeof record.type === 'object' ? record.type.name : undefined;
  if (!TYPES.has(type)) fail(`record ${index} has unknown type`);
  const numeric = record.stats && typeof record.stats === 'object' ? record.stats.numeric : undefined;
  if (!numeric || typeof numeric !== 'object') fail(`record ${index} has no numeric stats`);
  const sides = {};
  for (const side of ['top', 'right', 'bottom', 'left']) {
    sides[side] = integer(numeric[side], `record ${index} ${side}`, { min: 1, max: 10 });
  }
  return { id, publicNumber, name, stars, type, ...sides, salePrice };
}

function validateRecords(payload) {
  if (!payload || typeof payload !== 'object' || !Array.isArray(payload.results)) fail('response results must be an array');
  if (payload.results.length !== 475) fail(`expected 475 records, got ${payload.results.length}`);
  const cards = payload.results.map(cardFromRecord);
  const ids = new Set(cards.map(card => card.id));
  if (ids.size !== cards.length) fail('duplicate internal id');
  const numbers = new Set(cards.map(card => card.publicNumber));
  if (numbers.size !== cards.length) fail('duplicate public number');
  for (let number = 1; number <= 460; number += 1) if (!numbers.has(`No. ${number}`)) fail(`missing No. ${number}`);
  for (let number = 1; number <= 15; number += 1) if (!numbers.has(`Ex. ${number}`)) fail(`missing Ex. ${number}`);
  return cards.sort((a, b) => a.id - b.id);
}

function csvField(value) {
  const textValue = String(value);
  return /[",\r\n]/.test(textValue) ? `"${textValue.replaceAll('"', '""')}"` : textValue;
}

function csv(cards) {
  return [CSV_HEADER.join(','), ...cards.map(card => [
    card.id, card.publicNumber, card.name, card.stars, card.type,
    card.top, card.right, card.bottom, card.left, card.salePrice,
  ].map(csvField).join(','))].join('\n') + '\n';
}

function sourceText({ retrievedAt, sha256, patches }) {
  return [
    `Source URL: ${SOURCE_URL}`,
    `Retrieved at: ${retrievedAt}`,
    `Upstream patches: ${patches.join(', ') || '(none reported)'}`,
    `CSV SHA-256: ${sha256}`,
    '',
    'Attribution: FFXIV Collect (https://ffxivcollect.com/), XIVAPI (https://v2.xivapi.com/), and Square Enix.',
    'This text-only snapshot is for non-commercial use under the upstream acceptable-use terms.',
    'FFXIV Collect acceptable use: https://ffxivcollect.com/api/docs',
    'XIVAPI acceptable use: https://v2.xivapi.com/docs/welcome/',
    '',
  ].join('\n');
}

async function refresh({ fetchImpl = fetch, outputDir = path.resolve(__dirname, '../src/main/resources/catalog'), now = new Date() } = {}) {
  const response = await fetchImpl(SOURCE_URL);
  if (!response || !response.ok) fail(`HTTP request failed with status ${response && response.status}`);
  let payload;
  try {
    payload = await response.json();
  } catch (error) {
    fail(`response is not JSON: ${error.message}`);
  }
  const cards = validateRecords(payload);
  const csvText = csv(cards);
  const sha256 = crypto.createHash('sha256').update(csvText, 'utf8').digest('hex');
  const patches = [...new Set(cards.map(card => payload.results.find(record => record.id === card.id).patch).filter(Boolean))].sort();
  await fs.mkdir(outputDir, { recursive: true });
  await fs.writeFile(path.join(outputDir, 'cards.csv'), csvText, 'utf8');
  await fs.writeFile(path.join(outputDir, 'source.txt'), sourceText({ retrievedAt: now.toISOString(), sha256, patches }), 'utf8');
  return { count: cards.length, csvSha256: sha256, retrievedAt: now.toISOString(), patches };
}

if (require.main === module) {
  refresh().then(result => {
    process.stdout.write(`Wrote ${result.count} cards (SHA-256 ${result.csvSha256})\n`);
  }).catch(error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}

module.exports = { SOURCE_URL, validateRecords, csv, refresh };
