import streamlit as st
import pathlib

st.set_page_config(page_title="Scots Pine Bark", layout="wide")

html = (pathlib.Path(__file__).parent / "preview.html").read_text()

st.components.v1.html(html, height=800, scrolling=False)
