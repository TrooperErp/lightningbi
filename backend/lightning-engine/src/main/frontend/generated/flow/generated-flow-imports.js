import '@vaadin/common-frontend/ConnectionIndicator.js';
import 'Frontend/generated/jar-resources/ReactRouterOutletElement.tsx';
import 'react-router';
import 'react';

const loadOnDemand = (key) => {
  const pending = [];
  if (key === '20177c7fbddc3cbb2ad8a9de8f440f79cb12c66b35bf4a53949539ad577e04da') {
    pending.push(import('./chunks/chunk-06cfe298f2b72c1aeefe361c3c22a00e93b92731f66bd62f2b03168f97b2e45e.js'));
  }
  if (key === '6200366fed7c7273360acb3003ed785a36114178aa190b855ed43428396b8f51') {
    pending.push(import('./chunks/chunk-b867a353ce45039e53daebbd2c9b19e4443a267ad654f5a27fedf5a2b550a7f3.js'));
  }
  return Promise.all(pending);
}

window.Vaadin = window.Vaadin || {};
window.Vaadin.Flow = window.Vaadin.Flow || {};
window.Vaadin.Flow.loadOnDemand = loadOnDemand;
window.Vaadin.Flow.resetFocus = () => {
 let ae=document.activeElement;
 while(ae&&ae.shadowRoot) ae = ae.shadowRoot.activeElement;
 return !ae || ae.blur() || ae.focus() || true;
}