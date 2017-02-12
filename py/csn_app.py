from flask import Flask, request, render_template

import json

import base64

foo = base64.b64encode('Hello World'.encode('utf-8'))
print(foo)

app = Flask(__name__)

@app.route('/')
def display():
    return "You found teh root!"

@app.route('/alpha')
def alpha():
    return "You found teh alpha!"

@app.route('/beta')
def beta():
    return ('',200)

@app.route('/feedhandler/<id>', methods=['GET','POST'])
def feedhandler(id):
    print('feedhandler id:',id)
    print('feedhander method:',request.method)
    
    if request.method == 'GET':
        return downlink(request)
    elif request.method == 'POST':
        return handle_post(request)
    else:
        return('',400)

def handle_post(request):

    rq = json.loads(request.get_data().decode('utf-8'))

    print(rq)

    print('jsonrpc:',rq['jsonrpc'])

    print('jsonrpc method:',rq['method'])

    jsonrpc_method = rq['method']

    if jsonrpc_method == 'uplink':

        return uplink(rq)

    elif jsonrpc_method == 'downlink':

        return downlink(rq)
    
    return ('',400)

def uplink(rq):

    payload_b64 = rq['params']['payload']

    print('uplink data from',rq['params']['dev_eui'],base64.b64decode(payload_b64))

    return('',200)


def downlink(rq):

    print('downlink for device:', rq['params']['dev_eui'])
   
#    name=request.form['yourname']
#    email=request.form['youremail']
#    return json.dumps( { 'payload': base64.b64encode('Hello World'.encode('utf-8')) } )
#    return json.dumps( { 'payload': base64.b64encode('ACK'.encode('utf-8')).decode('utf-8') } ) 
    return ('',200)

if __name__=='__main__':
    app.run(host='0.0.0.0', debug=True, port=8099)
