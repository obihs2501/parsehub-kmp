import React from 'react';
import ReactDOM from 'react-dom/client';
import { App } from './App';
import './css/preflight.css';
import './css/base.css';

class RenderBoundary extends React.Component<React.PropsWithChildren, { error: boolean }> {
  state = { error: false };
  static getDerivedStateFromError() { return { error: true }; }
  render() {
    if (this.state.error) return <div className="boot-screen"><h1>界面发生错误</h1><p>请重新载入应用。已保存的文件和设置不会丢失。</p><button onClick={() => location.reload()}>重新载入</button></div>;
    return this.props.children;
  }
}
ReactDOM.createRoot(document.getElementById('root')!).render(<RenderBoundary><App /></RenderBoundary>);
