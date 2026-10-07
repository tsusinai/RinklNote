<script setup lang="ts">
withDefaults(defineProps<{ name: string; size?: number }>(), { size: 20 })
const paths: Record<string, string> = {
  book: 'M4 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-2H4z M13 7a3 3 0 0 1 3-3h4v15h-3a4 4 0 0 0-4 2',
  grid: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  left: 'M14 6l-6 6 6 6', right: 'M10 6l6 6-6 6',
  play: 'm9 5 10 7-10 7z', pause: 'M8 5v14 M16 5v14',
  expand: 'M8 3H3v5 M16 3h5v5 M21 16v5h-5 M3 16v5h5',
  close: 'm6 6 12 12 M6 18 18 6',
  note: 'M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5',
  clock: 'M12 8v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  check: 'm5 12 4 4L19 6',
  phone: 'M7 2h10a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1 M10 18h4',
  web: 'M3 4h18v16H3z M3 8h18 M6 6h1 M9 6h1',
  chat: 'M4 4h16v12H9l-5 4z M8 8h8 M8 12h5',
  leaf: 'M19 3C6 2 2 8 6 16c8 4 14-1 13-13z M4 21 15 9',
  chart: 'M4 3v17h17 M8 16v-5 M13 16V7 M18 16V4',
  wallet: 'M4 6h16v14H4z M4 6V4h13v2 M15 11h6v5h-6z',
  shield: 'm12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6z m-4 10 3 3 5-6',
  spark: 'm12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3z',
  sync: 'M4 11a8 8 0 0 1 14-5l2 2 M20 3v5h-5 M20 13A8 8 0 0 1 6 18l-2-2 M4 21v-5h5',
  print: 'M7 8V3h10v5 M7 17H3V8h18v9h-4 M7 14h10v7H7z',
  coffee: 'M5 8h11v8a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4z M16 9h2a3 3 0 0 1 0 6h-2 M8 2v3 M12 2v3',
  plus: 'M12 5v14 M5 12h14',
  target: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0',
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.book" /></svg></template>
