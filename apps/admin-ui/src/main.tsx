// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import {readLoginLink,clearLoginLink} from './LoginLink';
import './style.css';
import { applyTheme, themePreference } from './Theme';

const initialLogin=readLoginLink();
clearLoginLink();
applyTheme(themePreference());

createRoot(document.getElementById('root')!).render(<StrictMode><App initialLogin={initialLogin} /></StrictMode>);
