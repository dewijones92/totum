import { strings } from './strings.ts';

export function App() {
  return (
    <main className="shell">
      <h1>{strings.appName}</h1>
      <p>{strings.tagline}</p>
    </main>
  );
}
