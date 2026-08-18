# Face Recognition Model — archived prototype

> **Not used by the running system.** This is a research prototype kept for
> reference. Face recognition in the live application is DeepFace, called
> through [`Backend/Face_Recognation/services.py`](../../Backend/Face_Recognation/services.py).
> Nothing in the backend or the Android app imports or executes this notebook.
>
> `Face_ID.ipynb` is stored with its outputs cleared, so it is source only —
> run it in Colab to reproduce the results described below.

## What it was

We developed this Face Recognition Model to implement One-Shot Face Verification functionality by trying to build a Siamese Neural Network. To develop the neural network we took reference from the research paper — [Siamese Neural Networks for One-shot Image Recognition by Gregory Koch, Richard Zemel and Ruslan Salakhutdinov](https://www.cs.cmu.edu/~rsalakhu/papers/oneshot1.pdf).

We employed advanced libraries like **TensorFlow** and **Keras** to assist in developing the neural network. The training dataset was created by modifying the well-known **Labeled Faces in the Wild** dataset.

## Why it was set aside

The primary issue arose from the hardware limitations of the Face Recognition model. Initially, when constructing the Siamese Neural Network, we anticipated sufficient resources for model training. However, when the desired outcomes weren't achieved, we identified flaws in our approach. Training the model for improved results required multiple epochs. However, our complex model, with thousands of neurons processing thousands of images sized at 105 x 105 x 3, necessitated nearly ten epochs, which surpassed our hardware capabilities. Consequently, we had to adapt the entire functionality initially designed around our model to accommodate a pre-built face recognition library.

## What replaced it

Three generations, of which only the last is live:

| Approach | Outcome |
|---|---|
| Siamese network (this notebook) | Set aside — training exceeded available hardware |
| `face_recognition` + dlib | Replaced — required compiling dlib from source, which made the project hard to set up |
| **DeepFace, behind a swappable backend** | **Current.** Installs from PyPI with no build step |

The current implementation deliberately hides the library behind an interface
(`BaseFaceBackend`) so a future model — including a revived version of this one
— can be dropped in by adding a single class. See
[`Backend/Face_Recognation/README.md`](../../Backend/Face_Recognation/README.md).
