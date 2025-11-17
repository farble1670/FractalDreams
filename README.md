Fractal Dreams
===
Simple Android dream services that draws zooming, rotating Mandelbrot-based fractals. You'd never want to actually use this as a dream service as it'd make your phone very hot. There are two directions here. 

- Classes based on `FractalDreamService` are purely CPU based. I just wanted to see how far I could take performance. The results are marginal. There are lots of knobs and dials to adjust performance (with tradeoffs) in the code.
- `GL*` classes use the GPU by the was of OpenGL ES. 

 
