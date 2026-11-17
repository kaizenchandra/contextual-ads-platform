import './style.css';

export const metadata = {title: 'Contextual Ads Lab', description: 'Context-first advertising console'};
export default function Layout({children}: { children: React.ReactNode }) {
    return <html lang="en">
    <body>{children}</body>
    </html>
}