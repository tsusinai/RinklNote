import type { Directive } from 'vue'

export const reveal: Directive<HTMLElement> = {
  mounted(el) {
    el.classList.add('reveal')
    if (typeof IntersectionObserver === 'undefined') { el.classList.add('revealed'); return }
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => {
        if (e.isIntersecting) { el.classList.add('revealed'); io.disconnect() }
      })
    }, { threshold: 0.12 })
    io.observe(el)
  },
}
