import { useEffect, useId, useRef, type ReactNode } from 'react'

interface ConfirmCardProps {
  title: string
  children: ReactNode
  confirmLabel: string
  cancelLabel: string
  onConfirm: () => void
  onCancel: () => void
  danger?: boolean
}

/**
 * Inline consent card (the app has no modal dialogs): a title, a body and two buttons.
 * "Cancel" takes the focus when the card appears, so a stray Enter or tap never confirms;
 * Escape cancels as well.
 */
export function ConfirmCard({ title, children, confirmLabel, cancelLabel, onConfirm, onCancel, danger }: ConfirmCardProps) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const titleId = useId()
  const bodyId = useId()

  useEffect(() => {
    cancelRef.current?.focus()
  }, [])

  return (
    <div
      className="card"
      role="alertdialog"
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      style={{ borderColor: danger ? '#ef4444' : 'var(--accent)' }}
      onKeyDown={e => { if (e.key === 'Escape') onCancel() }}
    >
      <div id={titleId} style={{ fontSize: 15, fontWeight: 600, marginBottom: 8 }}>{title}</div>
      <div id={bodyId} style={{ fontSize: 13, lineHeight: 1.6, overflowWrap: 'anywhere', wordBreak: 'break-word' }}>
        {children}
      </div>
      {/* .btn has min-width:120px; let the pair shrink and wrap on a 360dp screen */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
        <button
          ref={cancelRef}
          className="btn btn-small btn-secondary"
          style={{ flex: '1 1 120px', minWidth: 0 }}
          onClick={onCancel}
        >
          {cancelLabel}
        </button>
        <button
          className={`btn btn-small ${danger ? 'btn-danger' : 'btn-primary'}`}
          style={{ flex: '1 1 120px', minWidth: 0 }}
          onClick={onConfirm}
        >
          {confirmLabel}
        </button>
      </div>
    </div>
  )
}
